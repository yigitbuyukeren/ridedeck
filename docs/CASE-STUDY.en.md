# RideDeck technical case study

🇹🇷 [Türkçe sürüm](CASE-STUDY.md) (the full version)

What problem RideDeck solves, how it is built, and why the technical decisions were made. Numbers and behaviour come from the code and CI; **anything not verified in the field is marked as such** (see [Verification status](#verification-status)).

## 1. Problem

On a motorcycle, bicycle or car, a phone has a small, glove-hostile screen, speed and navigation live in separate apps, and typing hurts safety. Android Auto and CarPlay need a car head unit, which a motorcycle does not have. RideDeck turns the phone itself into a landscape, large-target riding dashboard (it is also a HOME launcher). Goals: no account, API key or own backend; no text entry while moving; logic separable from Android so it can be unit-tested.

## 2. Architecture

Single-module Android app (Kotlin, Jetpack Compose, minSdk 26). One `MainActivity`, one Compose tree, no navigation library. `DashboardViewModel` is the only wiring point for all repositories.

- **Pure logic plus a thin Android shell.** `ThermalGuard`, `CrashDetector`, `SpeedFilter`, `PositionSmoother`, `VehicleProfile` and others take time and sensor data as input, so they are tested without a device.
- **Hand-written dependency injection.** `NavigationController` receives search, routing, speech and string lookup from its constructor; `NavigationControllerTest` runs without real services.
- **One network exit.** Every call to a public service (Nominatim, Valhalla, Open-Meteo, Overpass, OpenFreeMap) goes through `data/Http.kt`.
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

Explicit-submit Nominatim search (its policy forbids search-as-you-type), with rate-limited typo-tolerant retries and a "Did you mean…?" step that never routes without confirmation. Valhalla routing with `motorcycle`, `auto` or `bicycle` costing. Each GPS fix is projected onto the nearest segment inside an 80-segment window ahead of the last progress index; 60 m off the route for 4 consecutive fixes triggers a re-route. Announcements at 600 m (alert), 150 m (instruction) and 25 m (arrival), each spoken once. Every route/search request carries a generation so stale replies are dropped. Fuel/charging stops are routed as their own trip while the real destination is kept. Public Overpass servers are flaky, so queries race several mirrors and fall back to Nominatim.

## 5. Why there is no voice assistant

The app once had a Turkish voice assistant (command parser, multi-turn dialogue, helmet microphone, "Hey RideDeck" wake attempts). It was removed entirely on 2026-10-28: Android's `SpeechRecognizer` is not designed for continuous listening, so wake detection was not reliable, and the local wake model was not consistent enough on real speech. Removing a feature that did not work was preferred to showing it broken. Spoken guidance (turn prompts, weather and speed warnings, message reading) still uses Android TTS.

## 6. SOS / crash detection and its limits

`CrashDetector` needs recent riding speed **and** an impact above a per-vehicle threshold, followed by a quiet phone and a stopped vehicle. Potholes, hard stops and a bike knocked over in a car park stay quiet; on two wheels a tilt change (≥ 25°) or an impact twice the threshold is also required. After a countdown, one SMS per contact with a location link is sent; no call is placed.

**This is not a safety guarantee.** Thresholds (motorcycle 4 g, car 5 g, bicycle 3 g) are design assumptions and **have not been tuned in the field or tested in a real crash**. The feature is off by default, works only while the app is on screen, and false alarms and missed events are both possible.

## 7. Tests and CI

**396 unit tests in 48 files, 0 failures**, re-run cleanly locally while writing this. GitHub Actions runs `testDebugUnitTest lintDebug assembleDebug assembleRelease` (JDK 17) on every push to `main` and every PR; the release build catches R8 shrinking problems. Not covered by tests: Compose UI and real sensor/network behaviour (checked by hand on an emulator).

## 8. Key decisions

| Decision | Reason |
|---|---|
| No Play Services, keyless public services | Keeps "no account, no key, no server". Cost: own filtering/matching code, fallbacks for flaky services. |
| Display speed ≠ decision speed | A steady gauge and an undelayed safety decision are not traded against each other. |
| `--` instead of a fake 0 | No false confidence when the signal is lost. |
| Lock text entry and settings above 5 km/h | Riding safety. |
| Voice assistant removed | Reliable wake and recognition could not be achieved; removed rather than shown broken. |
| OBD, maintenance log, GPX, offline maps dropped | Unusable while riding or unverifiable. |

## Verification status

**Verified automatically:** 396 unit tests, `lintDebug`, debug and R8 release builds, CI. **Seen on an emulator:** main screens, vehicle chooser, navigation, fuel stops, SOS countdown, upright bicycle layout, font scaling. The README screenshots and demo GIF come from the emulator with simulated GPS; the GIF is a transition between screenshots, not a screen recording.

**Not tested in the field:** crash thresholds, real SMS sending, helmet Bluetooth mic, Turkish recognition quality in noise, in-call audio routing as default phone app, handlebar heat and mini-map battery cost, Android 8–10 behaviour. Current list: [STATUS.md](STATUS.md).
