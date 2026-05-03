# SafePath Indore

A safety-first navigation app for women and vulnerable users, built around three ideas:

1. **Crime-data-driven routing** — risk is computed from a real Indore crime dataset (`act302`, `act323`, `act363`, `act379`) and surfaced as one of three route options (Fastest / Balanced / Safest).
2. **Main-road priority** — every route waypoint is classified against a hand-curated set of Indore main-road corridors (AB Road, Ring Road, MR-10, Khandwa Road, Mhow Road, Bhawarkuan ↔ Palasia cross-corridor, Sapna Sangeeta inner ring) so the Safest route can prefer the spine network and dodge tertiary/residential streets.
3. **Google Maps integration** — the app handles risk-aware route *planning*; it then hands the chosen polyline (origin + up to 8 waypoints + destination) to the Google Maps app for actual turn-by-turn navigation.

Persistent SOS, Fake Call, Live Track, Heatmap, and an Unsafe-Area warning round out the safety toolkit.

---

## Tech stack

- **Kotlin**, **Android Studio Hedgehog / Iguana**, AGP 8.2, Gradle 8.4
- `compileSdk 34`, `minSdk 26`, `targetSdk 34`
- **Google Maps SDK** (`play-services-maps:18.2.0`)
- **Maps utils** (`maps-utils-android:3.8.2`) — only used for the heatmap layer
- **Fused Location** (`play-services-location:21.1.0`)
- **kotlinx-coroutines** for async dataset loading
- View Binding (no Jetpack Compose — keeps the APK small and the demo familiar to anyone who has built Android before)

No ML, no OSM, no graph libraries — everything is plain radius lookups + straight-line interpolation with perpendicular candidate offsets.

---

## Project layout

```
bgi/
├── app/
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── assets/
│   │   │   └── crime_data.csv          # 2 089 rows; ships inside the APK
│   │   ├── java/com/safepath/indore/
│   │   │   ├── MainActivity.kt         # Map + search + bottom panel + SOS hold
│   │   │   ├── data/
│   │   │   │   ├── CrimePoint.kt       # risk = 5×302 + 4×363 + 3×323 + 2×379, ×1.5 if hour ≥ 20
│   │   │   │   ├── CrimeDataLoader.kt  # CSV -> List<CrimePoint>
│   │   │   │   └── RiskCalculator.kt   # radius-based risk lookup + isHighRisk + routeRisk
│   │   │   ├── routing/
│   │   │   │   ├── RoadType.kt         # motorway 0, primary 1, secondary 2, tertiary 4, residential 6, service 8
│   │   │   │   ├── RoadNetwork.kt      # hand-curated Indore main-road corridors
│   │   │   │   ├── Route.kt
│   │   │   │   └── RouteGenerator.kt   # the 3 cost functions + perpendicular candidate search
│   │   │   ├── ui/
│   │   │   │   ├── FakeCallActivity.kt
│   │   │   │   ├── SosActivity.kt
│   │   │   │   └── LiveTrackActivity.kt
│   │   │   └── utils/
│   │   │       ├── GeoUtils.kt         # haversine, perpendicular offset, polyline sampling
│   │   │       └── Geocoder.kt         # offline lookup of well-known Indore landmarks
│   │   └── res/                        # layouts, themes, drawables, launcher icons
│   └── proguard-rules.pro
├── build.gradle.kts                    # plugins (project)
├── settings.gradle.kts
├── gradle.properties                   # MAPS_API_KEY placeholder
└── README.md
```

---

## How the routing works (one paragraph)

For an `(origin, destination)` pair the generator builds three polylines, each with eleven waypoints. The Fastest polyline is the straight-line interpolation. The Balanced and Safest polylines re-pick each intermediate waypoint from a small set of perpendicular offsets (±150–500 m), keeping whichever offset minimises that route's cost. The Safest variant additionally adds the *projection of the baseline waypoint onto the nearest main-road corridor* as a candidate, which biases the path toward AB Road / Ring Road / MR-10 / etc. Risk and road penalty are scaled before being added to distance so the cost terms are commensurate. The Stick-to-Main-Roads toggle doubles the road penalty for tertiary/residential/service classes outside the 5 %–95 % progress band (the "~500 m near start/end" carve-out from the spec).

---

## How to build & run

### Option A — Open in Android Studio (recommended)

1. **Get a Google Maps API key.**
   - Go to <https://console.cloud.google.com>, create / pick a project, enable **Maps SDK for Android**, then create an API key.
   - For demo purposes the key can be unrestricted; for a real build, restrict it by package name `com.safepath.indore` and the SHA-1 of your debug keystore.
2. Open `gradle.properties` and replace `YOUR_GOOGLE_MAPS_API_KEY` with your key.
3. Open the `bgi/` folder in Android Studio (Hedgehog 2023.1.1 or newer). Let it sync — it will download the Gradle wrapper, AGP 8.2, and all dependencies.
4. Plug in a phone (USB debugging on) **or** start an emulator with Google Play services (any "Pixel / Play" image, API 30+). Hit **Run ▶**.

### Option B — Command line

```bash
cd bgi/
# One-time: generate the Gradle wrapper if it isn't present.
gradle wrapper --gradle-version 8.4

# Pass your Maps key inline (or set it in gradle.properties).
./gradlew assembleDebug -PMAPS_API_KEY=AIza...your_key_here...

# APK ends up here:
ls -lh app/build/outputs/apk/debug/app-debug.apk

# Install on a connected device:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Option C — Release / signed APK for the demo

```bash
./gradlew assembleRelease -PMAPS_API_KEY=AIza...
# -> app/build/outputs/apk/release/app-release-unsigned.apk

# (Optional) sign it with a debug keystore so it installs:
$ANDROID_HOME/build-tools/<ver>/apksigner sign \
    --ks ~/.android/debug.keystore \
    --ks-pass pass:android \
    --out app-release.apk \
    app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## Demo flow (90 seconds)

1. Launch the app — map centres on Indore, the **🛡 Shield Secured** chip is visible at the top.
2. If you're in a flagged grid cell, the **⚠ Unsafe area detected nearby** chip appears next to it and a red 250 m circle is drawn around your location.
3. Type `Vijay Nagar` (or `Palasia`, `Bhawarkuan`, `IIT Indore`, `Airport`, etc.) in the search bar and press the search key on the IME.
4. Three routes appear simultaneously — **blue Fastest**, **yellow Balanced**, **green Safest**. The bottom panel shows distance + total risk for each. Tap any chip (or the polyline itself) to select it.
5. Toggle **Stick to Main Roads**. The Safest polyline visibly hugs AB / Ring / MR-10 corridors more aggressively.
6. Tap **Heatmap** — a red→yellow→green density layer of the 2 089 crime points appears.
7. Tap **Navigate in Google Maps** — the selected route's waypoints are encoded into a `https://www.google.com/maps/dir/?api=1&...` deep link and opened in the Google Maps app.
8. **Hold** the floating red **SOS** button for 2 seconds — the progress bar fills, the device vibrates, and the SOS confirmation screen takes over.
9. Tap **📞 Fake Call** — system ringtone, accept/decline UI, fake call timer.
10. Tap **📍 Live Track** — "Tracking Active" with last-known coordinate, ticking every 5 s.

---

## Demo-time tips

- Run the emulator with **Extended Controls → Location** set to `22.7196, 75.8577` (Rajwada) — that's already in the dataset's footprint, so risk numbers will be non-zero out of the gate.
- If the map shows up grey, your Maps API key is wrong / not enabled / not yet propagated. Re-check that **Maps SDK for Android** is enabled in Google Cloud, then `Build → Clean Project → Rebuild`.
- The APK works fully offline once installed — the dataset is bundled in `assets/`; only the *Maps tiles* themselves need internet.

---

## Performance notes

- Loading 2 089 rows on cold start takes ~30 ms on a mid-range device.
- Risk lookups bound the crime list with a lat/lng bbox before doing haversine — typical lookup is sub-millisecond.
- Route generation samples 11 waypoints × ~7 candidate offsets × 100 m route sampling. End-to-end, generating all three routes is well under 200 ms.

That's the whole stack — no background services, no ML inference, nothing that would stutter on stage.
