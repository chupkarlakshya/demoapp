"""SafePath backend.

Stdlib-only HTTP server backed by SQLite.

Reads config from `.env` at the repo root. See `.env.example` for the
expected fields.
"""

import base64
import http.server
import json
import os
import sqlite3
import time
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from urllib.parse import parse_qs, urlparse

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR = os.path.dirname(BASE_DIR)
DATA_DIR = os.path.join(BASE_DIR, "data")
DB_FILE = os.path.join(DATA_DIR, "safepath.db")
SEED_FILE = os.path.join(DATA_DIR, "seed.json")
LEGACY_JSON_FILE = os.path.join(DATA_DIR, "safepath.json")
PUBLIC_DIR = os.path.join(BASE_DIR, "public")


# -------------------------------------------------------------- env loader ---

def _load_env():
    """Tiny `.env` parser so we don't add python-dotenv as a dep."""
    env_path = os.path.join(ROOT_DIR, ".env")
    if not os.path.exists(env_path):
        return
    with open(env_path, "r", encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                continue
            key, _, val = line.partition("=")
            key = key.strip()
            val = val.strip().strip('"').strip("'")
            # Don't clobber values explicitly set in the shell.
            os.environ.setdefault(key, val)


_load_env()

PORT = int(os.environ.get("PORT", "8787"))
TWILIO_SID = os.environ.get("TWILIO_ACCOUNT_SID", "")
TWILIO_TOKEN = os.environ.get("TWILIO_AUTH_TOKEN", "")
TWILIO_FROM = os.environ.get("TWILIO_FROM_NUMBER", "")
DEMO_MODE = os.environ.get("DEMO_MODE", "true").lower() in ("1", "true", "yes", "on")
ADMIN_USER = os.environ.get("ADMIN_USER", "admin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "admin")
RISK_MODEL_PATH = os.path.join(
    BASE_DIR, os.environ.get("RISK_MODEL_PATH", "data/risk_model.joblib")
)


# ----------------------------------------------------------------- twilio ---

def send_sms(to_number, message):
    """Send an SMS through Twilio. No-op when DEMO_MODE is on."""
    if DEMO_MODE:
        print(f"[DEMO_MODE] would SMS {to_number}: {message}")
        return True
    if not (TWILIO_SID and TWILIO_TOKEN and TWILIO_FROM and to_number):
        print("[twilio] missing config or recipient — skipping send")
        return False
    if not TWILIO_SID.startswith("AC"):
        print("[twilio] SID looks invalid — skipping send")
        return False

    url = f"https://api.twilio.com/2010-04-01/Accounts/{TWILIO_SID}/Messages.json"
    payload = urllib.parse.urlencode(
        {"To": to_number, "From": TWILIO_FROM, "Body": message}
    ).encode("utf-8")
    auth = base64.b64encode(f"{TWILIO_SID}:{TWILIO_TOKEN}".encode("utf-8")).decode("ascii")

    req = urllib.request.Request(url, data=payload, method="POST")
    req.add_header("Authorization", f"Basic {auth}")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    try:
        with urllib.request.urlopen(req, timeout=8) as f:
            return f.status == 201
    except Exception as e:
        print(f"[twilio] error: {e}")
        return False


# ---------------------------------------------------------------- sqlite ---

SCHEMA = """
CREATE TABLE IF NOT EXISTS incidents (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL,
    description TEXT,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    severity INTEGER DEFAULT 3,
    status TEXT DEFAULT 'pending',
    reported_by TEXT,
    created_at TEXT NOT NULL,
    reviewed_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_incidents_status ON incidents(status);
CREATE INDEX IF NOT EXISTS idx_incidents_created ON incidents(created_at);

CREATE TABLE IF NOT EXISTS sos_events (
    id TEXT PRIMARY KEY,
    user_id TEXT,
    contact TEXT,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    status TEXT DEFAULT 'active',
    created_at TEXT NOT NULL,
    resolved_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_sos_status ON sos_events(status);
CREATE INDEX IF NOT EXISTS idx_sos_created ON sos_events(created_at);

CREATE TABLE IF NOT EXISTS risk_zones (
    id TEXT PRIMARY KEY,
    name TEXT,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    radius_meters REAL,
    risk_score REAL,
    source TEXT,
    updated_at TEXT
);
"""


def db_connect():
    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def init_db():
    if not os.path.exists(DATA_DIR):
        os.makedirs(DATA_DIR)
    fresh = not os.path.exists(DB_FILE)
    conn = db_connect()
    try:
        conn.executescript(SCHEMA)
        conn.commit()
        if fresh:
            _seed_db(conn)
    finally:
        conn.close()


def _seed_db(conn):
    """Seed the DB. Pulls from the legacy safepath.json if present, otherwise seed.json."""
    source = LEGACY_JSON_FILE if os.path.exists(LEGACY_JSON_FILE) else SEED_FILE
    if not os.path.exists(source):
        return
    with open(source, "r", encoding="utf-8") as f:
        seed = json.load(f)
    cur = conn.cursor()
    for item in seed.get("incidents", []):
        cur.execute(
            "INSERT OR IGNORE INTO incidents (id, type, description, latitude, longitude, severity, status, reported_by, created_at, reviewed_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                item.get("id") or f"inc_seed_{uuid.uuid4().hex[:8]}",
                str(item.get("type", "OTHER")).upper(),
                item.get("description", ""),
                float(item.get("latitude", item.get("lat", 0))),
                float(item.get("longitude", item.get("lng", 0))),
                int(item.get("severity", 3)),
                item.get("status", "pending"),
                item.get("reportedBy", "demo"),
                item.get("createdAt", datetime.now(timezone.utc).isoformat() + "Z"),
                item.get("reviewedAt"),
            ),
        )
    for item in seed.get("sosEvents", []):
        cur.execute(
            "INSERT OR IGNORE INTO sos_events (id, user_id, contact, latitude, longitude, status, created_at, resolved_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (
                item.get("id") or f"sos_seed_{uuid.uuid4().hex[:8]}",
                item.get("userId", "anonymous"),
                item.get("contact", ""),
                float(item.get("latitude", item.get("lat", 0))),
                float(item.get("longitude", item.get("lng", 0))),
                item.get("status", "active"),
                item.get("createdAt", datetime.now(timezone.utc).isoformat() + "Z"),
                item.get("resolvedAt"),
            ),
        )
    for item in seed.get("riskZones", []):
        cur.execute(
            "INSERT OR IGNORE INTO risk_zones (id, name, latitude, longitude, radius_meters, risk_score, source, updated_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (
                item.get("id") or f"zone_seed_{uuid.uuid4().hex[:8]}",
                item.get("name", ""),
                float(item.get("latitude", 0)),
                float(item.get("longitude", 0)),
                float(item.get("radiusMeters", 200)),
                float(item.get("riskScore", 50)),
                item.get("source", "seed"),
                item.get("updatedAt", datetime.now(timezone.utc).isoformat() + "Z"),
            ),
        )
    conn.commit()


def _row_to_incident(row):
    return {
        "id": row["id"],
        "type": row["type"],
        "description": row["description"] or "",
        "latitude": row["latitude"],
        "longitude": row["longitude"],
        "severity": row["severity"],
        "status": row["status"],
        "reportedBy": row["reported_by"],
        "createdAt": row["created_at"],
        "reviewedAt": row["reviewed_at"],
    }


def _row_to_sos(row):
    return {
        "id": row["id"],
        "userId": row["user_id"],
        "contact": row["contact"],
        "latitude": row["latitude"],
        "longitude": row["longitude"],
        "status": row["status"],
        "createdAt": row["created_at"],
        "resolvedAt": row["resolved_at"],
    }


def _row_to_zone(row):
    return {
        "id": row["id"],
        "name": row["name"],
        "latitude": row["latitude"],
        "longitude": row["longitude"],
        "radiusMeters": row["radius_meters"],
        "riskScore": row["risk_score"],
        "source": row["source"],
        "updatedAt": row["updated_at"],
    }


def get_overview(conn):
    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM incidents")
    total = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM incidents WHERE status='verified'")
    verified = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM incidents WHERE status='pending'")
    pending = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM sos_events WHERE status='active'")
    active_sos = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM risk_zones WHERE risk_score >= 65")
    high_zones = cur.fetchone()[0]
    return {
        "totalIncidents": total,
        "verifiedReports": verified,
        "pendingReports": pending,
        "activeSos": active_sos,
        "highRiskZones": high_zones,
        "lastUpdated": datetime.now(timezone.utc).isoformat() + "Z",
    }


# -------------------------------------------------------------- ML serving ---

_risk_model = None
_risk_model_load_attempted = False


def get_risk_model():
    global _risk_model, _risk_model_load_attempted
    if _risk_model_load_attempted:
        return _risk_model
    _risk_model_load_attempted = True
    if not os.path.exists(RISK_MODEL_PATH):
        print(f"[risk-model] not found at {RISK_MODEL_PATH} — /api/risk will return 503")
        return None
    try:
        import joblib  # local import; backend works without it if model missing
        _risk_model = joblib.load(RISK_MODEL_PATH)
        print(f"[risk-model] loaded from {RISK_MODEL_PATH}")
    except Exception as e:
        print(f"[risk-model] load failed: {e}")
        _risk_model = None
    return _risk_model


def score_risk(latitude, longitude, hour, day_of_week):
    model = get_risk_model()
    if model is None:
        return None
    try:
        import numpy as np
        X = np.array([[latitude, longitude, hour, day_of_week]])
        score = float(model.predict(X)[0])
        # Clamp to 0-100 for the app.
        return max(0.0, min(100.0, score))
    except Exception as e:
        print(f"[risk-model] predict failed: {e}")
        return None


# ---------------------------------------------------------------- handler ---

class SafePathHandler(http.server.BaseHTTPRequestHandler):

    # --- shared helpers ---------------------------------------------------

    def end_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header(
            "Access-Control-Allow-Methods", "GET, POST, PATCH, OPTIONS"
        )
        self.send_header(
            "Access-Control-Allow-Headers", "Content-Type, Authorization"
        )
        super().end_headers()

    def log_message(self, fmt, *args):
        # Slightly tidier console output than the default.
        print("[%s] %s" % (self.log_date_time_string(), fmt % args))

    def send_json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json_body(self):
        length = int(self.headers.get("Content-Length", 0))
        if length <= 0:
            return {}
        raw = self.rfile.read(length).decode("utf-8")
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {}

    def is_admin(self):
        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return False
        try:
            decoded = base64.b64decode(header.split(" ", 1)[1]).decode("utf-8")
        except Exception:
            return False
        if ":" not in decoded:
            return False
        user, _, pw = decoded.partition(":")
        return user == ADMIN_USER and pw == ADMIN_PASS

    def require_admin(self):
        if self.is_admin():
            return True
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="SafePath Admin"')
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"error":"unauthorized"}')
        return False

    def handle_one_request(self):
        """Override to provide a global catch-all for 500 errors."""
        try:
            super().handle_one_request()
        except Exception as e:
            print(f"[server] critical error: {e}")
            try:
                self.send_error(500, str(e))
            except Exception:
                pass

    # --- verbs ------------------------------------------------------------

    def do_OPTIONS(self):
        self.send_response(204)
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path.startswith("/api/"):
            self.handle_api_get(parsed.path, parse_qs(parsed.query))
        else:
            self.handle_static(parsed.path)

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path.startswith("/api/"):
            self.handle_api_post(parsed.path)
        else:
            self.send_error(404)

    def do_PATCH(self):
        parsed = urlparse(self.path)
        if parsed.path.startswith("/api/"):
            self.handle_api_patch(parsed.path)
        else:
            self.send_error(404)

    # --- static -----------------------------------------------------------

    # Pages that need admin auth (the dashboard's mutation endpoints already
    # require it; gating the HTML keeps random URL-pasters out too).
    _PROTECTED_PAGES = {"/", "/index.html", "/response.html"}

    def handle_static(self, path):
        # Normalize path for case-insensitive check (Windows security)
        norm_path = path.lower()
        if norm_path == "/":
            norm_path = "/index.html"
            
        is_protected = any(norm_path == p.lower() for p in self._PROTECTED_PAGES)
        if is_protected and not self.require_admin():
            return

        if path == "/":
            path = "/index.html"
        file_path = os.path.normpath(os.path.join(PUBLIC_DIR, path.lstrip("/")))
        # Prevent path traversal.
        if not file_path.startswith(PUBLIC_DIR):
            self.send_error(403)
            return
        if not os.path.isfile(file_path):
            self.send_error(404, "File not found")
            return

        content_type = "application/octet-stream"
        if path.endswith(".html"):
            content_type = "text/html"
        elif path.endswith(".css"):
            content_type = "text/css"
        elif path.endswith(".js"):
            content_type = "application/javascript"

        with open(file_path, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    # --- GET routes -------------------------------------------------------

    def handle_api_get(self, path, query):
        conn = db_connect()
        try:
            if path == "/api/health":
                self.send_json(200, {"ok": True, "service": "SafePath", "demoMode": DEMO_MODE})

            elif path == "/api/overview":
                self.send_json(200, get_overview(conn))

            elif path == "/api/incidents":
                status = query.get("status", [None])[0]
                if status:
                    rows = conn.execute(
                        "SELECT * FROM incidents WHERE status=? ORDER BY created_at DESC",
                        (status,),
                    ).fetchall()
                else:
                    rows = conn.execute(
                        "SELECT * FROM incidents ORDER BY created_at DESC"
                    ).fetchall()
                self.send_json(200, [_row_to_incident(r) for r in rows])

            elif path == "/api/sos":
                rows = conn.execute(
                    "SELECT * FROM sos_events ORDER BY created_at DESC"
                ).fetchall()
                self.send_json(200, [_row_to_sos(r) for r in rows])

            elif path == "/api/risk-zones":
                rows = conn.execute("SELECT * FROM risk_zones").fetchall()
                seeded = [_row_to_zone(r) for r in rows]
                verified = conn.execute(
                    "SELECT * FROM incidents WHERE status='verified'"
                ).fetchall()
                derived = []
                for v in verified:
                    sev = int(v["severity"] or 3)
                    derived.append({
                        "id": f"report_zone_{v['id']}",
                        "name": (v["type"] or "OTHER").replace("_", " "),
                        "latitude": v["latitude"],
                        "longitude": v["longitude"],
                        "radiusMeters": 220 + sev * 40,
                        "riskScore": min(100, 45 + sev * 10),
                        "source": "verified crowd report",
                        "updatedAt": v["reviewed_at"] or v["created_at"],
                    })
                self.send_json(200, seeded + derived)

            elif path == "/api/risk":
                # Score a single (lat, lng, [hour], [day]).
                try:
                    lat = float(query.get("lat", [None])[0])
                    lng = float(query.get("lng", [None])[0])
                except (TypeError, ValueError):
                    self.send_json(400, {"error": "lat and lng required"})
                    return
                
                # Default to now if missing
                now = datetime.now(timezone.utc)
                hour = int(query.get("hour", [now.hour])[0])
                day = int(query.get("day", [now.weekday()])[0])
                score = score_risk(lat, lng, hour, day)
                if score is None:
                    self.send_json(503, {"error": "risk model not available; train it first"})
                    return
                self.send_json(200, {
                    "lat": lat, "lng": lng, "hour": hour, "day": day,
                    "riskScore": score,
                })

            elif path == "/api/risk-grid":
                # Grid scoring for the app's heatmap overlay.
                try:
                    min_lat = float(query.get("minLat", ["22.65"])[0])
                    max_lat = float(query.get("maxLat", ["22.80"])[0])
                    max_lng = float(query.get("maxLng", ["75.95"])[0])
                    
                    steps = max(8, min(32, int(query.get("steps", ["20"])[0])))
                    now = datetime.now(timezone.utc)
                    hour = int(query.get("hour", [now.hour])[0])
                    day = int(query.get("day", [now.weekday()])[0])
                except (ValueError, TypeError, IndexError):
                    self.send_json(400, {"error": "bad query parameters"})
                    return

                model = get_risk_model()
                if model is None:
                    self.send_json(503, {"error": "risk model not available; train it first"})
                    return

                import numpy as np
                lats = np.linspace(min_lat, max_lat, steps)
                lngs = np.linspace(min_lng, max_lng, steps)
                grid = []
                Xs = []
                for la in lats:
                    for ln in lngs:
                        Xs.append([la, ln, hour, day])
                preds = model.predict(np.array(Xs))
                idx = 0
                for la in lats:
                    for ln in lngs:
                        score = max(0.0, min(100.0, float(preds[idx])))
                        grid.append({"lat": float(la), "lng": float(ln), "score": score})
                        idx += 1
                self.send_json(200, {
                    "hour": hour, "day": day, "steps": steps, "cells": grid,
                })
            else:
                self.send_error(404)
        finally:
            conn.close()

    # --- POST routes ------------------------------------------------------

    def handle_api_post(self, path):
        body = self.read_json_body()
        conn = db_connect()
        try:
            if path == "/api/incidents":
                try:
                    lat = float(body.get("latitude", body.get("lat")))
                    lng = float(body.get("longitude", body.get("lng")))
                except (TypeError, ValueError):
                    self.send_json(400, {"error": "latitude and longitude required"})
                    return
                new_id = f"inc_{int(time.time())}_{uuid.uuid4().hex[:6]}"
                conn.execute(
                    "INSERT INTO incidents (id, type, description, latitude, longitude, severity, status, reported_by, created_at) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        new_id,
                        str(body.get("type", "OTHER")).upper(),
                        str(body.get("description", ""))[:500],
                        lat,
                        lng,
                        min(5, max(1, int(body.get("severity", 3)))),
                        body.get("status", "pending"),
                        body.get("reportedBy", "anonymous"),
                        datetime.now(timezone.utc).isoformat() + "Z",
                    ),
                )
                conn.commit()
                row = conn.execute("SELECT * FROM incidents WHERE id=?", (new_id,)).fetchone()
                self.send_json(201, _row_to_incident(row))

            elif path == "/api/sos":
                try:
                    lat = float(body.get("latitude", body.get("lat")))
                    lng = float(body.get("longitude", body.get("lng")))
                except (TypeError, ValueError):
                    self.send_json(400, {"error": "latitude and longitude required"})
                    return
                new_id = f"sos_{int(time.time())}_{uuid.uuid4().hex[:6]}"
                contact = str(body.get("contact", ""))
                conn.execute(
                    "INSERT INTO sos_events (id, user_id, contact, latitude, longitude, status, created_at) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (
                        new_id,
                        body.get("userId", "anonymous"),
                        contact,
                        lat,
                        lng,
                        "active",
                        datetime.now(timezone.utc).isoformat() + "Z",
                    ),
                )
                conn.commit()

                # Fan out SMS — to the user's saved contact and to a 100/108
                # advisory list. Suppressed entirely if DEMO_MODE.
                maps_link = f"https://www.google.com/maps/search/?api=1&query={lat},{lng}"
                msg = f"EMERGENCY SOS from SafePath Indore. Location: {maps_link}"
                sms_results = []
                if contact:
                    sms_results.append({"to": contact, "ok": send_sms(contact, msg)})
                # Optional fan-out list from env, comma-separated.
                extra = [n.strip() for n in os.environ.get("SOS_FANOUT_NUMBERS", "").split(",") if n.strip()]
                for number in extra:
                    sms_results.append({"to": number, "ok": send_sms(number, msg)})

                row = conn.execute("SELECT * FROM sos_events WHERE id=?", (new_id,)).fetchone()
                payload = _row_to_sos(row)
                payload["smsDispatched"] = sms_results
                payload["demoMode"] = DEMO_MODE
                self.send_json(201, payload)

            elif path == "/api/notify":
                # Manual notify, admin only.
                if not self.require_admin():
                    return
                to = body.get("to", "")
                msg = body.get("message", "")
                ok = send_sms(to, msg)
                self.send_json(200 if ok else 500, {"success": ok, "demoMode": DEMO_MODE})
            else:
                self.send_error(404)
        finally:
            conn.close()

    # --- PATCH routes -----------------------------------------------------

    def handle_api_patch(self, path):
        # All mutations require admin.
        if not self.require_admin():
            return
        parts = path.strip("/").split("/")  # api/incidents/ID
        if len(parts) < 3:
            self.send_error(404)
            return
        resource, resource_id = parts[1], parts[2]
        patch = self.read_json_body()
        conn = db_connect()
        try:
            if resource == "incidents":
                row = conn.execute("SELECT * FROM incidents WHERE id=?", (resource_id,)).fetchone()
                if not row:
                    self.send_json(404, {"error": "not found"})
                    return
                new_status = patch.get("status", row["status"])
                new_sev = patch.get("severity", row["severity"])
                new_sev = min(5, max(1, int(new_sev))) if new_sev is not None else row["severity"]
                conn.execute(
                    "UPDATE incidents SET status=?, severity=?, reviewed_at=? WHERE id=?",
                    (new_status, new_sev, datetime.now(timezone.utc).isoformat() + "Z", resource_id),
                )
                conn.commit()
                updated = conn.execute("SELECT * FROM incidents WHERE id=?", (resource_id,)).fetchone()
                self.send_json(200, _row_to_incident(updated))

            elif resource == "sos":
                row = conn.execute("SELECT * FROM sos_events WHERE id=?", (resource_id,)).fetchone()
                if not row:
                    self.send_json(404, {"error": "not found"})
                    return
                new_status = patch.get("status", row["status"])
                resolved_at = (
                    datetime.now(timezone.utc).isoformat() + "Z"
                    if new_status == "resolved"
                    else row["resolved_at"]
                )
                conn.execute(
                    "UPDATE sos_events SET status=?, resolved_at=? WHERE id=?",
                    (new_status, resolved_at, resource_id),
                )
                conn.commit()
                updated = conn.execute("SELECT * FROM sos_events WHERE id=?", (resource_id,)).fetchone()
                self.send_json(200, _row_to_sos(updated))
            else:
                self.send_error(404)
        finally:
            conn.close()


# ----------------------------------------------------------------- entry ---

if __name__ == "__main__":
    init_db()
    get_risk_model()  # eager-load so the first request is fast
    server = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), SafePathHandler)
    print(f"SafePath backend running at http://localhost:{PORT}")
    print(f"  (Listening on all interfaces, DEMO_MODE={DEMO_MODE})")
    print(f"Admin login: {ADMIN_USER} / {'*' * len(ADMIN_PASS)}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    server.server_close()
