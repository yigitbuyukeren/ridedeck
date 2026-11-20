# RideDeck — IzzyOnDroid / F-Droid readiness (short report)

Date: 2026-11-20. Scope: **distribution metadata and verification only**. No store
application was filed; the existing APK, the `v0.7.0` tag and the GitHub release
were not touched. Full evidence: [FDROID-VERIFICATION.md](FDROID-VERIFICATION.md).

## Delivered in this change

- `fastlane/metadata/android/{en-US,tr-TR}/`: `title.txt`, `short_description.txt`,
  `full_description.txt`, `changelogs/8.txt` (v0.7.0 has versionCode 8 — fastlane
  changelogs are keyed by versionCode, not version name).
- Real v0.7.0 emulator screenshots and a 512×512 app icon under each locale's
  `images/`, plus `images/SCREENSHOT-PROVENANCE.md` documenting synthetic GPS.
- `scripts/check-fastlane-metadata.py`, wired into `.github/workflows/ci.yml`,
  enforcing structure, text limits, changelog naming, icon/screenshot sizes,
  synthetic-GPS disclosure and a no-secrets scan.

Positioning follows the request: open-source Android driving dashboard inspired
by CarPlay/Android Auto, currently motorcycle-focused, intended to expand to
other vehicles later. No CarPlay/Android Auto integration, offline routing or
verified crash/SOS behaviour is claimed.

## APK verification (PASS, local evidence)

| Item | Result |
|---|---|
| SHA-256 | `08e264b2…b845d` — matches `RELEASE_READINESS.md` |
| Signature | Valid, **v2 only**, 1 signer; cert SHA-256 `9d9164ef…22427` — matches documented value |
| Identity | `com.ridedeck.app`, versionCode 8 / 0.7.0, minSdk 26 / targetSdk 36, not debuggable |
| Permissions | 20 declared; no `RECORD_AUDIO`, background location, Bluetooth scan/connect, SMS-read or camera |
| Licenses | Apache-2.0 app + NOTICE; deps open (AndroidX/Compose, OkHttp, MapLibre BSD-2-Clause) |
| Contents | ARM-only natives, no analytics/ads/backend/test asset, zipalign PASS |

## Blockers / OPEN items

1. **Signing & reproducibility — OPEN.** Release signing depends on an uncommitted
   `keystore.properties`; a from-source F-Droid build would be signed by F-Droid and
   would not update in place over the certified GitHub APK. IzzyOnDroid's exact
   expectation needs a maintainer decision.
2. **Version conflict — OPEN.** versionCode 8 is already published as GitHub v0.7.0.
   Any new store build must bump versionCode (and add the matching changelog file).
3. **Provider terms — OPEN / partly BLOCKED.** Global quota compliance for
   OpenFreeMap, Nominatim, FOSSGIS/Valhalla/Overpass and Open-Meteo is not proven;
   new offline downloads/resume are paused and offline routing is unavailable.
4. **Store data-safety declaration — OPEN.** Location, SMS, call/contacts and
   notification-access capabilities plus third-party network providers need a
   written privacy/data-safety statement for the store listing.
5. **Field gates — OPEN / NO-GO.** Real device, real GPS/riding, SMS/SIM, calls,
   intercom, lock-screen/Doze/OEM and 16 KB runtime remain unverified. The project
   decision for public 1.0 stays NO-GO.

## Needed before the next release

- Close the field gates with real-device evidence; keep SOS described as experimental.
- Resolve provider terms/quotas before re-enabling any new offline downloads.
- Decide the store signing strategy (from-source vs. existing certificate) and a
  reproducible-build path; bump versionCode for any store build.
- Complete the store data-safety/privacy declaration and legal review of NOTICE.
- After those are closed, publish the fastlane metadata to the store repository and
  submit — **not done here**, per instruction.
