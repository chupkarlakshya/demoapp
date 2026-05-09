"""Train the SafePath risk regressor.

Reads the bundled crime CSV, generates a dense synthetic grid of
(lat, lng, hour, day_of_week) query points, computes the "true"
neighborhood-weighted risk at each grid point from the historical
crimes, and trains a GradientBoostingRegressor that maps
(lat, lng, hour, day_of_week) -> 0..100 risk score.

Output: backend/data/risk_model.joblib

Usage:
    python3 backend/train_risk_model.py
"""

import json
import os
import sqlite3
import sys
import time

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import GradientBoostingRegressor
from sklearn.metrics import r2_score
from sklearn.model_selection import KFold, cross_val_score, train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CSV_PATH = os.path.join(ROOT, "app/src/main/assets/crime_data.csv")
DB_PATH = os.path.join(HERE, "data/safepath.db")
MODEL_PATH = os.path.join(HERE, "data/risk_model.joblib")
META_PATH = os.path.join(HERE, "data/risk_model.meta.json")

NEIGHBORHOOD_RADIUS_M = 800.0
KERNEL_SIGMA_M = 350.0  # Gaussian falloff: ~70% mass within 500m
EARTH_RADIUS_M = 6_371_000.0

# Severity weights, mirroring the existing RiskCalculator.
ACT_WEIGHTS = {
    "act302": 5.0,  # murder
    "act363": 4.0,  # kidnapping
    "act323": 3.0,  # hurt / assault
    "act379": 2.0,  # theft
}
NIGHT_BOOST = 1.5  # hour >= 20

# Grid extent — covers the whole Indore footprint that the app uses.
GRID_MIN_LAT = 22.62
GRID_MAX_LAT = 22.82
GRID_MIN_LNG = 75.78
GRID_MAX_LNG = 75.98
LAT_STEPS = 36
LNG_STEPS = 36
HOUR_STEPS = 8       # bucket: 0,3,6,9,12,15,18,21
DAY_STEPS = 7


def load_crimes(path):
    df = pd.read_csv(path)
    # Handle multiple formats: 28-02-2018 21:00 and 1/3/2018 12:00
    df["ts"] = pd.to_datetime(df["timestamp"], errors="coerce", dayfirst=True)
    df = df.dropna(subset=["ts"]).reset_index(drop=True)

    df["hour"] = df["ts"].dt.hour.astype(int)
    df["day_of_week"] = df["ts"].dt.dayofweek.astype(int)

    weighted = sum(df[col] * w for col, w in ACT_WEIGHTS.items() if col in df.columns)
    df["raw_severity"] = weighted.astype(float).values
    df = df[df["raw_severity"] > 0].reset_index(drop=True)
    return df


def load_verified_crowd_reports(db_path=DB_PATH):
    """Augment historical CSV with verified user reports from SQLite.

    Each verified report contributes as a synthetic crime point. Severity
    (1..5) is multiplied by 4 to land in roughly the same scale as the
    CSV severity weights. Hour/day are derived from createdAt.
    """
    if not os.path.exists(db_path):
        return pd.DataFrame(columns=["latitude", "longitude", "hour", "day_of_week", "raw_severity"])
    conn = sqlite3.connect(db_path)
    try:
        rows = conn.execute(
            "SELECT latitude, longitude, severity, created_at FROM incidents WHERE status='verified'"
        ).fetchall()
    finally:
        conn.close()
    if not rows:
        return pd.DataFrame(columns=["latitude", "longitude", "hour", "day_of_week", "raw_severity"])

    out = []
    for lat, lng, sev, created_at in rows:
        try:
            ts = pd.to_datetime(created_at)
        except Exception:
            continue
        out.append({
            "latitude": float(lat),
            "longitude": float(lng),
            "hour": int(ts.hour),
            "day_of_week": int(ts.dayofweek),
            "raw_severity": float(sev or 3) * 4.0,
        })
    return pd.DataFrame(out)


def score_grid_against_crimes(grid_lat, grid_lng, grid_hour, grid_day, crimes):
    """Vectorized: for each grid point compute sum of weighted_risk of nearby crimes.

    Crime contribution at a grid point includes:
      - haversine distance ≤ NEIGHBORHOOD_RADIUS_M
      - soft hour-of-day match (events at similar times count more)
      - night multiplier on the GRID hour (if grid_hour ≥ 20, 1.5×)
    """
    n_grid = len(grid_lat)
    n_crime = len(crimes)
    print(f"  scoring grid: {n_grid} points × {n_crime} crimes …")

    crime_lat = np.radians(crimes["latitude"].values).astype(np.float32)
    crime_lng = np.radians(crimes["longitude"].values).astype(np.float32)
    crime_hour = crimes["hour"].values.astype(np.int8)
    crime_sev = crimes["raw_severity"].values.astype(np.float32)

    out = np.zeros(n_grid, dtype=np.float32)

    grid_lat_rad = np.radians(grid_lat).astype(np.float32)
    grid_lng_rad = np.radians(grid_lng).astype(np.float32)

    # Stream through grid points in chunks to keep memory bounded.
    chunk = 4096
    t0 = time.time()
    for start in range(0, n_grid, chunk):
        end = min(start + chunk, n_grid)
        glat = grid_lat_rad[start:end][:, None]
        glng = grid_lng_rad[start:end][:, None]
        ghr = grid_hour[start:end][:, None]
        gday_hour_boost = np.where(grid_hour[start:end] >= 20, NIGHT_BOOST, 1.0).astype(np.float32)

        dlat = glat - crime_lat[None, :]
        dlng = glng - crime_lng[None, :]
        a = (
            np.sin(dlat / 2) ** 2
            + np.cos(glat) * np.cos(crime_lat[None, :]) * np.sin(dlng / 2) ** 2
        )
        dist = 2 * EARTH_RADIUS_M * np.arcsin(np.sqrt(np.clip(a, 0, 1)))

        hdiff = np.abs(ghr - crime_hour[None, :]).astype(np.float32)
        hdiff = np.minimum(hdiff, 24 - hdiff)
        time_factor = np.maximum(0.2, 1 - hdiff / 12.0)

        # Gaussian kernel — every crime contributes, falling off with distance.
        # Cap by NEIGHBORHOOD_RADIUS_M to keep runtime bounded; weights past
        # ~3σ are negligible anyway.
        in_radius = (dist <= NEIGHBORHOOD_RADIUS_M).astype(np.float32)
        kernel = np.exp(-(dist ** 2) / (2 * KERNEL_SIGMA_M ** 2))
        contrib = in_radius * kernel * time_factor * crime_sev[None, :]
        out[start:end] = contrib.sum(axis=1) * gday_hour_boost

        if start == 0:
            elapsed = time.time() - t0
            est = elapsed * (n_grid / chunk)
            print(f"    first chunk {chunk}/{n_grid} in {elapsed:.1f}s (~{est:.0f}s total)")

    return out


def build_synthetic_grid():
    lats = np.linspace(GRID_MIN_LAT, GRID_MAX_LAT, LAT_STEPS, dtype=np.float32)
    lngs = np.linspace(GRID_MIN_LNG, GRID_MAX_LNG, LNG_STEPS, dtype=np.float32)
    hours = np.linspace(0, 21, HOUR_STEPS, dtype=np.int8)
    days = np.arange(DAY_STEPS, dtype=np.int8)

    LA, LN, HR, DY = np.meshgrid(lats, lngs, hours, days, indexing="ij")
    flat_lat = LA.ravel()
    flat_lng = LN.ravel()
    flat_hr = HR.ravel()
    flat_day = DY.ravel()
    return flat_lat, flat_lng, flat_hr, flat_day


def train(X, y):
    # log1p + max scaling: keeps zeros at zero, compresses heavy tail so
    # training samples cover a more uniform range, and the heatmap shows
    # gradient instead of being dominated by 1-2 hotspots.
    y_log = np.log1p(y.astype(np.float64))
    y_max = float(y_log.max() or 1.0)
    y_scaled = (y_log / y_max * 100.0).astype(np.float32)

    model = Pipeline([
        ("scaler", StandardScaler()),
        ("gbm", GradientBoostingRegressor(
            n_estimators=300,
            max_depth=4,
            learning_rate=0.05,
            subsample=0.85,
            random_state=42,
        )),
    ])

    # Honest holdout — randomly split grid points into train/test.
    X_tr, X_te, y_tr, y_te = train_test_split(X, y_scaled, test_size=0.2, random_state=42)

    cv = KFold(n_splits=5, shuffle=True, random_state=42)
    cv_scores = cross_val_score(model, X_tr, y_tr, cv=cv, scoring="r2", n_jobs=-1)
    print(f"  5-fold CV R² on train split: mean={cv_scores.mean():.3f} std={cv_scores.std():.3f}")

    model.fit(X_tr, y_tr)
    test_r2 = r2_score(y_te, model.predict(X_te))
    print(f"  Holdout test R²: {test_r2:.3f}")

    # Final fit on the full grid before saving.
    model.fit(X, y_scaled)
    return model, y_max, test_r2, cv_scores.mean()


def main():
    if not os.path.exists(CSV_PATH):
        print(f"crime CSV not found at {CSV_PATH}", file=sys.stderr)
        sys.exit(1)

    print(f"[1/4] loading {CSV_PATH}")
    crimes = load_crimes(CSV_PATH)
    print(f"  {len(crimes)} historical crime points")

    crowd = load_verified_crowd_reports()
    if len(crowd):
        print(f"  + {len(crowd)} verified crowd reports from SQLite")
        crimes = pd.concat([crimes, crowd], ignore_index=True)

    print("[2/4] building synthetic (lat, lng, hour, day) query grid")
    grid_lat, grid_lng, grid_hour, grid_day = build_synthetic_grid()
    print(f"  {len(grid_lat)} grid samples")

    print("[3/4] computing neighborhood-weighted risk targets")
    targets = score_grid_against_crimes(grid_lat, grid_lng, grid_hour, grid_day, crimes)
    print(f"  target range: [{targets.min():.2f}, {targets.max():.2f}], mean={targets.mean():.2f}")

    X = np.column_stack([grid_lat, grid_lng, grid_hour, grid_day]).astype(np.float32)
    y = targets

    print("[4/4] training GradientBoostingRegressor")
    model, scale, test_r2, cv_r2 = train(X, y)

    os.makedirs(os.path.dirname(MODEL_PATH), exist_ok=True)
    joblib.dump(model, MODEL_PATH)

    meta = {
        "modelType": "GradientBoostingRegressor",
        "features": ["latitude", "longitude", "hour", "day_of_week"],
        "targetRange": [0, 100],
        "neighborhoodRadiusMeters": NEIGHBORHOOD_RADIUS_M,
        "actWeights": ACT_WEIGHTS,
        "nightBoost": NIGHT_BOOST,
        "rawTargetMax": scale,
        "trainGrid": {
            "lat": [GRID_MIN_LAT, GRID_MAX_LAT, LAT_STEPS],
            "lng": [GRID_MIN_LNG, GRID_MAX_LNG, LNG_STEPS],
            "hour": HOUR_STEPS,
            "day": DAY_STEPS,
            "samples": int(len(grid_lat)),
        },
        "testR2": float(test_r2),
        "cvR2": float(cv_r2),
        "trainingCrimeRows": int(len(crimes)),
        "verifiedCrowdRows": int(len(crowd)),
    }
    with open(META_PATH, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)

    print(f"saved model -> {MODEL_PATH}")
    print(f"saved meta  -> {META_PATH}")


if __name__ == "__main__":
    main()
