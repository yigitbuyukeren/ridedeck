# RideDeck technical case study

🇹🇷 [Türkçe sürüm](CASE-STUDY.md) (the full version)

What problem RideDeck solves, how it is built, and why the technical decisions were made. Numbers and behaviour come from the code and CI; **anything not verified in the field is marked as such** (see [Verification status](#verification-status)).

## 1. Problem

On a motorcycle, a phone has a small, glove-hostile screen, speed and navigation live in separate apps, and typing hurts safety. Android Auto and CarPlay need a car head unit, which a motorcycle does not have. RideDeck turns the phone itself into a landscape, large-target riding dashboard. Goals: no account, API key or own backend; no text entry while moving; logic separable from Android so it can be unit-tested.

## 2. Architecture

Single-module Android app (Kotlin, Jetpack Compose, minSdk 26). One `MainActivity`, one Compose tree, no navigation library. `RideDeckApplication` owns one process-scoped `RideNavigation`, one `RideTracking` and one `RideSafety`; `DashboardViewModel` binds screen state but does not own the repositories.

- **Pure logic plus a thin Android shell.** `ThermalGuard`, `CrashDetector`, `SpeedFilter`, `PositionSmoother`, the motorcycle profile and others take time and sensor data as input, so they are tested without a device.
- **Hand-written dependency injection.** `NavigationController` receives search, routing, speech and string lookup from its constructor; `NavigationControllerTest` runs without real services.
- **One network exit.** Nominatim, Valhalla, Open-Meteo and Overpass calls go through `data/Http.kt`/`ProviderHttp`: one versioned `User-Agent`, cancellable calls, and per-provider concurrency/spacing limits. MapLibre tile and offline-download requests are made by the map engine itself. These limits shape only this app's traffic; they do not prove aggregate provider compliance.
- **Shared GPS.** `LocationArbiter` reference-counts demand from the screen, a started ride service and the overlay; the first owner starts the one listener, the last release stops it, so one owner leaving cannot cut GPS for the others.
- **Lifetime.** Navigation, TTS and a started ride live in the application process and survive Activity/ViewModel recreation. Guidance continues with the screen off; after process death or force-stop the route returns only as a preview, and does not resume by itself.
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

Explicit-submit Nominatim search (its policy forbids search-as-you-type), with central 1 req/s spacing, rate-limited typo-tolerant retries and a "Did you mean…?" step that never routes without confirmation. Valhalla routing always uses `motorcycle` costing; avoid-highways and avoid-tolls are remembered. Each GPS fix is projected onto the nearest segment inside a window that widens with the GPS gap and speed (at least 80 segments); 45–150 m off the route for 3–4 consecutive fixes triggers a re-route. Three fresh fixes with a bearing mismatch raise a wrong-way/U-turn warning, and progress cannot jump backwards. Announcements scale with speed: far warning ~45 s (400 m–2 km), full instruction ~11 s (100–450 m), short "now" ~3 s (20–60 m), each spoken once. Arrival is the route end ≤25 m, stopping at the destination, or losing GPS at the door; a remaining-distance guard prevents early arrival. Every route/search request carries a generation so stale replies are dropped, and pending route/alternative/continue work is cancelled by ownership. A fuel stop is routed as its own trip while the real destination is kept. Public Overpass servers are flaky, so a query goes to at most two distinct operators (FOSSGIS and kumi/private.coffee) in parallel, never two connections to the same operator, and falls back to a Nominatim corridor. A fresh route older than 10 minutes, or a GPS position outside its first 500 m corridor, cannot begin guidance; a restored cached preview is stricter and allows only the first 50 m; weather backs off exponentially before its first snapshot and speed-limit lookups back off after failures and expire after 60 s.

## 5. Why there is no voice assistant

The app once had a Turkish voice assistant (command parser, multi-turn dialogue, helmet microphone, "Hey RideDeck" wake attempts). It was removed entirely on 2026-10-28: Android's `SpeechRecognizer` is not designed for continuous listening, so wake detection was not reliable, and the local wake model was not consistent enough on real speech. Removing a feature that did not work was preferred to showing it broken. Spoken guidance (turn prompts, weather and speed warnings, message reading) still uses Android TTS.

## 6. SOS / crash detection and its limits

`CrashDetector` needs recent riding speed **and** an impact above the motorcycle threshold, followed by a quiet phone and a stopped vehicle. Potholes, hard stops and a motorcycle knocked over in a car park stay quiet; on two wheels a tilt change (≥ 25°) or an impact twice the threshold is also required. After a countdown, one SMS per contact (up to 3) with a location link is sent; no call is placed.

**This is not a safety guarantee.** The 4 g motorcycle threshold is a design assumption and **has not been tuned in the field or tested in a real crash**. The feature is off by default; sensors listen while the app is on screen or a started ride's foreground service runs. A countdown that starts with the screen off is raised over the keyguard with a notification, full-screen intent and wake lock; the notification offers **I'm OK** for the countdown and **Close** for the result. If there is no notification the app is launched when the OS permits a background activity start (overlay access on Android 10+); if FSI is denied the high-priority notification remains while sound/vibration/SMS still run. Real locked-device behaviour, OEM FSI denial and SIM/operator delivery remain unverified; carrier SMS charges may apply. False alarms and missed events are both possible.

## 7. Tests and CI

See [the release readiness report](RELEASE_READINESS.md) for the latest recorded JVM, lint and emulator instrumentation results. Unit tests cover GPS filtering, map matching and route logic, typo tolerance, SOS/crash detection and the SMS result model, thermal guard, phone/keypad math, units, trip storage, SQLite migration/corruption backup, provider throttling/backoff and shared GPS ownership.

GitHub Actions runs `test lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest bundleRelease`, release APK alignment, AAB existence, debug x86_64/ARM ABI and release ARM-only checks, and gated signing. A separate job runs instrumentation on an x86_64 API 35 emulator with KVM access, a Pixel 6 profile, a single cold boot, a 20-minute step timeout and the cached-app freezer disabled; the permission-denied service test runs separately. `RideDeckTestRunner` ends the test process after results. Not covered by tests: real sensor/GPS/network behaviour, physical keyguard/Doze/OEM, real SMS/call/intercom and 16 KB runtime (an emulator or synthetic test is not a substitute).

## 8. Key decisions

| Decision | Reason |
|---|---|
| No Play Services, keyless public services | Keeps "no account, no key, no server". Cost: own filtering/matching code and fallbacks; app-local limits do not prove aggregate provider compliance. |
| One process GPS stream with a reference-counted `LocationArbiter` | Screen, started ride service and overlay share one listener; no second subscription, and one owner leaving cannot cut GPS for the others. |
| Display speed ≠ decision speed | A steady gauge and an undelayed safety decision are not traded against each other. |
| `--` instead of a fake 0 | No false confidence when the signal is lost. |
| Lock text entry and settings at or above 5 km/h | Riding safety. |
| Voice assistant removed | Reliable wake and recognition could not be achieved; removed rather than shown broken. |
| No offline routing; map downloads stay behind a provider gate | Routing and search stay online. New downloads/resume remain **BLOCKED** until OpenFreeMap terms and aggregate compliance are proven; existing areas are preserved. |
| OBD, maintenance log, GPX dropped | Unusable while riding or unverifiable. |

## Verification status

**Automated verification** is recorded in [the release readiness report](RELEASE_READINESS.md).

**Seen on an emulator:** main screens, navigation, fuel stops, SOS countdown, font scaling. The vehicle chooser and upright bicycle layout no longer exist. The README screenshots and demo GIF come from the emulator with simulated GPS; the GIF is a transition between screenshots, not a screen recording.

**Not tested in the field:** crash thresholds, real SMS sending and SIM/operator delivery, physical keyguard/Doze/OEM, in-call audio routing as default phone app and intercom, handlebar heat and mini-map battery cost, Android 8–10 behaviour, 16 KB runtime, store/Data safety and aggregate provider quota compliance. Current list: [STATUS.md](STATUS.md) and [RELEASE_READINESS.md](RELEASE_READINESS.md). The overall 1.0 decision is **NO-GO**.
