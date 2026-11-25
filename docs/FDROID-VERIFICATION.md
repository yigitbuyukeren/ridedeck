# F-Droid / IzzyOnDroid verification — RideDeck 0.7.0 signed release APK

Date: 2026-11-20. Scope: **local evidence only** — the APK at
`/home/notrace/Projeler/ridedeck/dist/RideDeck-0.7.0.apk`, the repo sources/docs at
`/home/notrace/Projeler/ridedeck-fastlane` (branch `chore/fdroid-izzyondroid-metadata`),
and local Android SDK tools. No network, no store submission, no keystore/secret reads.
No APK, tag, release, branch, version or commit was modified.

Tools: `sha256sum`, `$ANDROID_HOME/build-tools/35.0.0/{apksigner,aapt,zipalign}`,
`unzip -l/-p`, repo text files, and the local Gradle POM cache (MapLibre license).

## Summary table

| # | Check | Result | Concrete evidence |
|---|---|---|---|
| 1 | APK exists / non-empty / hash | **PASS** | 10,448,486 bytes; SHA-256 `08e264b2c6a1235418aa89ed4646074dca71677a4ca01e5ea031811d084b845d` = value in `RELEASE_READINESS.md` line 13 |
| 2 | Signature valid + cert | **PASS** | `apksigner verify`: Verifies; **v2 only** (v1/v3/v3.1/v4 = false); 1 signer; DN `CN=RideDeck Release v2, OU=Android, O=RideDeck, C=TR`; cert SHA-256 `9d9164ef8c679f2909e2a851d1327839c684cad238ca0932488a9f8d0f622427` = documented value; RSA 4096 |
| 3 | Identity / version / manifest | **PASS** | `com.ridedeck.app`, versionCode `8`, versionName `0.7.0`, compile 36; minSdk `26`, targetSdk `36`; no `android:debuggable` (release default false), `allowBackup=false`, `fullBackupContent=0`, `dataExtractionRules` set; matches `app/build.gradle.kts` |
| 4 | Permissions | **PASS (absences)** / **OPEN (runtime gating)** | `RECORD_AUDIO`, `ACCESS_BACKGROUND_LOCATION`, `BLUETOOTH_SCAN/CONNECT/ADVERTISE`, `READ_SMS`/`RECEIVE_SMS`, `CAMERA` all absent; 20 declared permissions listed below; dangerous ones are feature-scoped in docs but runtime behaviour is not locally verifiable |
| 5 | Licenses / F-Droid build inputs | **PASS (local)** / **OPEN (provider ToS)** | Apache-2.0 app + NOTICE; all compile deps openly licensed (MapLibre BSD-2-Clause confirmed via local POM); no analytics/ads/proprietary SDK/backend/secret assets in APK; provider commercial terms remain OPEN |
| 6 | Store suitability | **OPEN** | ARM-only ABI, v2 developer signature, no debug/test assets, zipalign PASS; but keystore not committed (from-source signing/reproducibility), provider quotas, data-safety declaration and real-device gates are unresolved; repo decision remains **NO-GO** |

No FAIL items were found in the locally verifiable checks.

## 1. File hash and documented comparison

- Path: `/home/notrace/Projeler/ridedeck/dist/RideDeck-0.7.0.apk` — exists, regular file, **10,448,486 bytes** (non-empty).
- `sha256sum`: `08e264b2c6a1235418aa89ed4646074dca71677a4ca01e5ea031811d084b845d`.
- `docs/RELEASE_READINESS.md`, top (2026-11-18) section, line 13 records the identical APK SHA-256. **MATCH.**
- The same line records the identical certificate SHA-256 used in check 2.

## 2. Signature (`apksigner verify --verbose --print-certs`)

```
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
Signer #1 certificate DN: CN=RideDeck Release v2, OU=Android, O=RideDeck, C=TR
Signer #1 certificate SHA-256 digest: 9d9164ef8c679f2909e2a851d1327839c684cad238ca0932488a9f8d0f622427
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 4096
```

- Signature scheme: **APK Signature Scheme v2 only** (consistent with the "v2 imzalı" note in `RELEASE_READINESS.md`).
- Certificate SHA-256 **exactly matches** the documented value; signer DN is a RideDeck release identity.
- Only certificate/key digests were printed — no keystore, password or private-key material was read or emitted.

## 3. Identity, version and manifest flags

`aapt dump badging`:
```
package: name='com.ridedeck.app' versionCode='8' versionName='0.7.0' platformBuildVersionCode='36'
sdkVersion:'26'   targetSdkVersion:'36'
native-code: 'arm64-v8a' 'armeabi-v7a'
```
`aapt dump xmltree ... AndroidManifest.xml` (application element): no `android:debuggable`
attribute (= release default false), `android:allowBackup=0`, `android:fullBackupContent=0`,
`dataExtractionRules` and `localeConfig` set. Launcher activity `com.ridedeck.app.MainActivity`
exported as required.

Cross-check with `app/build.gradle.kts`: `applicationId=com.ridedeck.app`, `minSdk=26`,
`targetSdk=36`, `versionCode=8`, `versionName="0.7.0"`, `compileSdk=36` — **all match**.

Additional static facts: `zipalign -c -P 16 4` **PASS**; `META-INF/version-control-info.textproto`
embeds revision `7a53557ce21083b51998474b97087e3ee0f5662c` (present in repo history). This differs
from the current worktree HEAD `3da099c6d7fd540a9421c6038241b8834958f4d2` — expected, since the
verified file is a release artifact produced from a different checkout; it is recorded here as a
fact, not treated as a mismatch of the APK itself.

## 4. Permissions

Declared (20 `uses-permission` + 1 app-defined permission):

- Network: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` (last one attributed to a dependency in `RELEASE_READINESS.md`).
- Location: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`.
- Call/telecom: `READ_PHONE_STATE`, `ANSWER_PHONE_CALLS`, `CALL_PHONE`, `READ_CALL_LOG`.
- Contacts/SMS: `READ_CONTACTS`, `SEND_SMS`.
- Notifications/FGS: `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `USE_FULL_SCREEN_INTENT`, `VIBRATE`.
- Overlay/other: `SYSTEM_ALERT_WINDOW`, `WAKE_LOCK`.
- AndroidX: `com.ridedeck.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.

**Unusual permissions confirmed ABSENT:** `RECORD_AUDIO`, `ACCESS_BACKGROUND_LOCATION`,
`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `READ_SMS`, `RECEIVE_SMS`,
`CAMERA`, `READ_EXTERNAL_STORAGE` / media-store write. (Consistent with
`RELEASE_READINESS.md` line 98 and the "no new Bluetooth scan/connect or mic permission" notes.)

Dangerous permissions and feature gating per docs (`FEATURES-REFERENCE.md` "Gizlilik ve izinler",
`RELEASE_READINESS.md`): location and FGS are used for active ride tracking/navigation started by
the user; `SEND_SMS` is used only by the user-triggered SOS flow; call permissions route intercom
audio via Telecom (no app-originated dialling beyond user action); `SYSTEM_ALERT_WINDOW` is an
explicit user-enabled overlay preference; `POST_NOTIFICATIONS` is a runtime permission.
`CarNotificationListener` is protected by `BIND_NOTIFICATION_LISTENER_SERVICE` and must be enabled
by the user in notification-access settings — a sensitive capability worth noting in any store
data-safety declaration. `READ_CONTACTS` is declared although docs describe SOS contacts being
chosen with the system picker; the exact runtime use cannot be established from the APK alone
(**OPEN** for behaviour).

## 5. Dependency licenses / F-Droid suitability

- App license **Apache-2.0**: `LICENSE` (full text) and `NOTICE` (copyright 2026 Yiğit Eren Büyükeren, Apache-2.0 + third-party attributions).
- Compile dependencies in `app/build.gradle.kts` and their licenses per `NOTICE` / local POM cache:
  - AndroidX + Jetpack Compose (activity, lifecycle, core, palette, compose BOM 2025.06.01) — Apache-2.0.
  - OkHttp 4.12.0 — Apache-2.0.
  - Kotlin stdlib / coroutines — Apache-2.0.
  - MapLibre Android SDK 11.13.5 — **BSD-2-Clause**, confirmed from the locally cached POM (`<url>https://opensource.org/licenses/BSD-2-Clause</url>`); native `.so` files are built from open source.
  - Map data/styles: OSM ODbL 1.0, OpenMapTiles (code BSD-3-Clause, cartography CC BY 4.0), OpenFreeMap tiles.
- No proprietary SDK, closed-source blob, ads, crash reporting, analytics or app-owned backend was
  found in the dependency declarations, `NOTICE`, `FEATURES-REFERENCE.md` privacy section or APK
  contents. External services are public/keyless (OpenFreeMap, Nominatim, Valhalla/FOSSGIS,
  Overpass, Open-Meteo, system TTS) and send request-scoped data + IP as documented.
- APK contents (`unzip -l`): only `assets/dexopt/baseline.prof` and `baseline.profm` under `assets/`;
  native libs `lib/arm64-v8a/libmaplibre.so` (~13.1 MB), `lib/armeabi-v7a/libmaplibre.so` (~9.5 MB)
  and `libandroidx.graphics.path.so` for both ABIs; `classes.dex`, resources, `resources.arsc`,
  AndroidX `LICENSE.txt` entries under `META-INF/`. **No** probe/debug/fixture/test asset, no
  `google-services.json`, no Firebase/Crashlytics/ads assets, no keystore/`.jks`/`.p12`/`.pem`/token/`.env`
  file, no suspicious bundled asset. The CI "no probe asset in release" gate is consistent with this.
- **Verifiable locally:** license texts/attributions, dependency list, absence of telemetry/ads/proprietary
  SDK files, native `.so` identity and license, no debug/test asset leak, zipalign, signature.
- **OPEN (not verifiable locally):** provider Terms of Service and commercial terms (OpenFreeMap,
  Nominatim OSMF global 1 req/s, FOSSGIS Valhalla/Overpass limits, Open-Meteo non-commercial terms),
  whether every artifact is buildable in F-Droid's isolated build environment, and store data-safety wording.

## 6. IzzyOnDroid / F-Droid store suitability

Facts supporting suitability:
- Release ABI is ARM-only (`arm64-v8a`, `armeabi-v7a`); no x86_64 in release (documented and intentional).
- targetSdk 36, minSdk 26, versionCode 8 / versionName 0.7.0; v2 signature with a stable developer
  certificate; R8 minify + resource shrink enabled; no `debuggable`; no test-only/probe asset;
  `zipalign -c -P 16 4` PASS; no debug wiring in the release manifest.

Blockers / unknowns:
- **Signing / reproducibility (OPEN):** `app/build.gradle.kts` signs release only when an uncommitted
  `keystore.properties` exists; otherwise the build is unsigned. A from-source F-Droid build would
  therefore need F-Droid's own signing key, which breaks continuity with the certified v0.7.0 APK.
  IzzyOnDroid's exact signing/reproducibility expectation for this app cannot be confirmed locally.
- **Provider ToS / quotas (OPEN, partly BLOCKED in `RELEASE_READINESS.md`):** global provider quota
  compliance is not proven by in-app throttling; Open-Meteo is non-commercial; new downloads/resume
  are currently closed.
- **Store decision / data safety (OPEN):** no store data-safety declaration or F-Droid metadata
  (description, changelogs, screenshots, license metadata) was assessed here.
- **Real-device gates (OPEN/UNVERIFIED):** physical device, real GPS/driving, real SMS/SIM,
  intercom, Doze/OEM, lock-screen and 16 KB runtime are unverified; `RELEASE_READINESS.md` keeps the
  public-release decision at **NO-GO**.
- Version conflict risk (OPEN): versionCode 8 is already published as GitHub v0.7.0; any store build
  must bump the version code before a new submission (this report makes no version change).

**Bottom line:** the signed APK itself is sound and internally consistent with the repo's release
record (hash, cert, version, ABI, permissions, licenses, no debug/test leaks). The remaining items
are store/policy, signing-infrastructure and real-device questions, not defects found in the APK.

## Readiness summary & blockers (2026-11-20)

- **Fastlane metadata:** `fastlane/metadata/android/{en-US,tr-TR}/` (`title.txt`, `short_description.txt`, `full_description.txt`, `changelogs/8.txt`, v0.7.0 emulator screenshots, 512×512 icon, `SCREENSHOT-PROVENANCE.md`) validated by `scripts/check-fastlane-metadata.py` in CI.
- **Open blockers before submission:**
  1. **Signing & reproducibility (OPEN):** Release signing uses uncommitted `keystore.properties`; decide from-source F-Droid vs. existing certificate strategy.
  2. **Version conflict (OPEN):** `versionCode 8` is already published as GitHub `v0.7.0`; bump `versionCode` and add matching changelog for any store build.
  3. **Provider terms (OPEN/BLOCKED):** Global quota compliance for OpenFreeMap, Nominatim, FOSSGIS/Valhalla/Overpass and Open-Meteo is unproven; new offline downloads remain paused.
  4. **Store data-safety & legal (OPEN):** Complete privacy/data-safety declarations and `NOTICE` review.
  5. **Field gates (OPEN/NO-GO):** Real device, GPS/riding, SMS/SIM, calls, intercom, lock-screen/Doze/OEM and 16 KB runtime remain unverified.
