# RideDeck technical case study

🇹🇷 [Türkçe sürüm](CASE-STUDY.md) (the full version)

> **Status note (2026-11-17):** This case study describes the design at a particular point in development. The app is now motorcycle-only; the vehicle picker, bicycle/car profiles and HOME launcher support have been removed. For current behaviour, test counts and open work, see [README](../README.md), [STATUS.md](STATUS.md) and [ARCHITECTURE.md](ARCHITECTURE.md).

What problem RideDeck solves, how it is built, and why the technical decisions were made. Numbers and behaviour come from the code and CI; **anything not verified in the field is marked as such** (see [Verification status](#verification-status)).

## 1. Problem

On a motorcycle, a phone has a small, glove-hostile screen, speed and navigation live in separate apps, and typing hurts safety. Android Auto and CarPlay need a car head unit, which a motorcycle does not have. RideDeck turns the phone itself into a landscape, large-target riding dashboard. Goals: no account, API key or own backend; no text entry while moving; logic separable from Android so it can be unit-tested.

## 2. Architecture

Single-module Android app (Kotlin, Jetpack Compose, minSdk 26). One `MainActivity`, one Compose tree, no navigation library. `DashboardViewModel` is the only wiring point for all repositories.

- **Pure logic plus a thin Android shell.** `ThermalGuard`, `CrashDetector`, `SpeedFilter`, `PositionSmoother`, `VehicleProfile` and others take time and sensor data as input, so they are tested without a device.
- **Hand-written dependency injection.** `NavigationController` receives search, routing, speech and string lookup from its constructor; `NavigationControllerTest` runs without real services.
- **One network exit.** Every call to a public HTTP service (Nominatim, Valhalla, Open-Meteo, Overpass) goes through `data/Http.kt`; MapLibre's OpenFreeMap tile requests stay in the map engine.
- Raw `LocationManager`, no Play Services.

## 3. GPS filtering

`SpeedFilter` is a low-gain alpha-beta filter over the 1 Hz GPS speed, used **for display only**:

- inside the noise band a measurement only nudges the estimate; outside it, the change is believed;
- an impossible jump (> 15 m/s²) or a > 3 m/s jump while the chip reports poor accuracy is held back **once**, a second in a row is believed; a drop explainable by braking (≤ 9 m/s²) pulls 70 % of the way, so a real hard brake is not a fix late;
- stop hysteresis (two slow fixes to stop, a clearly higher speed to move) avoids 0/1/0 flicker at a red light;
- no fix for 3 s while moving means *unknown* (`--`, "GPS signal lost"), never a made-up 0;
- crash detection, trip recording and the limit warning use the **raw** speed so the filter cannot delay them;
- `PositionSmoother` dead-reckons the map marker between fixes.

## 4. Navigation

Explicit-submit Nominatim search (its policy forbids search-as-you-type), with rate-limited typo-tolerant retries and a "Did you mean…?" step that never routes without confirmation. Valhalla routing always uses the `motorcycle` costing. Each GPS fix is projected onto the nearest segment inside an 80-segment window ahead of the last progress index; 60 m off the route for 4 consecutive fixes triggers a re-route. Announcements use three stages (distance alert, full instruction, short "now"), each spoken once. Every route/search request carries a generation so stale replies are dropped. Fuel stops are routed as their own trip while the real destination is kept. Public Overpass servers are flaky, so queries race several mirrors and fall back to Nominatim.

## 5. Why there is no voice assistant

The app once had a Turkish voice assistant (command parser, multi-turn dialogue, helmet microphone, "Hey RideDeck" wake attempts). It was removed entirely on 2026-10-28: Android's `SpeechRecognizer` is not designed for continuous listening, so wake detection was not reliable, and the local wake model was not consistent enough on real speech. Removing a feature that did not work was preferred to showing it broken. Spoken guidance (turn prompts, weather and speed warnings, message reading) still uses Android TTS.

## 6. SOS / crash detection and its limits

`CrashDetector` needs recent riding speed **and** an impact above the motorcycle threshold, followed by a quiet phone and a stopped vehicle. Potholes, hard stops and a motorcycle knocked over in a car park stay quiet; on two wheels a tilt change (≥ 25°) or an impact twice the threshold is also required. After a countdown, one SMS per contact (up to 3) with a location link is sent; no call is placed.

**This is not a safety guarantee.** The motorcycle threshold (4 g) is a design assumption and **has not been tuned in the field or tested in a real crash**. The feature is off by default and works while the app is on screen or an explicitly started ride (foreground service) is running. False alarms and missed events are both possible.

## 7. Tests and CI

This case study originally described **396 unit tests in 48 files, 0 failures**. Current counts and results live in [STATUS.md](STATUS.md). GitHub Actions runs `test lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest` (JDK 17) on every push to `main` and every PR. The instrumentation tests are compiled but not run on a device; run `connectedDebugAndroidTest` separately. The release build catches R8 shrinking problems. Instrumentation test classes cover Compose screens and some service/permission flows; real sensor/network behaviour, physical devices and field conditions are not covered automatically.

## 8. Key decisions

| Decision | Reason |
|---|---|
| No Play Services, keyless public services | Keeps "no account, no key, no server". Cost: own filtering/matching code, fallbacks for flaky services. |
| Display speed ≠ decision speed | A steady gauge and an undelayed safety decision are not traded against each other. |
| `--` instead of a fake 0 | No false confidence when the signal is lost. |
| Lock text entry and settings above 5 km/h | Riding safety. |
| Voice assistant removed | Reliable wake and recognition could not be achieved; removed rather than shown broken. |
| OBD, maintenance log, GPX dropped; offline routing not built | Unusable while riding or unverifiable. Existing map areas are kept, but new offline downloads are paused until provider compliance is verified; routing and search stay online. |

## Verification status

**Verified automatically:** the original release had 396 unit tests, `lintDebug`, debug and R8 release builds, and CI; current counts are in [STATUS.md](STATUS.md). **Seen on an emulator:** main screens, navigation, fuel stops, SOS countdown, font scaling. The README screenshots and demo GIF come from the emulator with simulated GPS; the GIF is a transition between screenshots, not a screen recording.

**Not tested in the field:** crash thresholds, real SMS sending, in-call audio routing as the default phone app, handlebar heat and mini-map battery cost, Android 8–10 behaviour. Current list: [STATUS.md](STATUS.md).
