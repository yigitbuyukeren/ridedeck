# Screenshot provenance — v0.7.0

## Files and source

The four phone screenshots are unmodified copies of:

`/home/notrace/Projeler/ridedeck/captures/launch-v0.7.0/sources/`

| fastlane file | Source file | Screen | Dimensions |
|---|---|---|---|
| `1.png` | `01-dashboard.png` | Dashboard / speedometer, mini map, music card | 1600×900 PNG |
| `2.png` | `02-route.png` | Route planning over a map with route options | 1600×900 PNG |
| `3.png` | `03-navigation.png` | Active navigation / map and manoeuvre card | 1600×900 PNG |
| `4.png` | `04-music.png` | Music player panel | 1600×900 PNG |

The same four bytes were placed in `en-US/images/phoneScreenshots/` and `tr-TR/images/phoneScreenshots/`.

## Capture evidence (local, not committed with these images)

- The captures are dated **2026-11-18** and live in a folder named for **v0.7.0**.
- The capture run's working copy (`/tmp/opencode/ridedeck-v070-launch-run/source`) was at tag **`v0.7.0`**, commit `691c457` (2026-11-18 11:34 +0300), and its `emulator.log` records `androidboot.qemu.avd_name=ridedeck-v070-launch`.
- The four source PNGs are **byte-identical (MD5)** to the run's raw emulator screencaps `dashboard-moving.png`, `route-framed.png`, `nav-moving.png` and `music.png`. No retouching or AI generation was applied; the `x/` subfolder contains only cropped derivatives (1600×880) made by `export.py`, and the `aydinlik/`/`karanlik/` subfolders are themed copies of the same run's raw captures used for the theme collages.
- `export.py` and `theme-collages.py` in that run caption the images **"Android emülatörü · Sentetik GPS · Yerel demo müzik"** — "Android emulator · Synthetic GPS · Local demo music". This is the explicit statement that the displayed location was simulated, not real riding.
- Project `docs/DEVELOPMENT.md` documents `adb emu geo fix 29.0230 41.0500 30 12 15` (longitude, latitude) as the way to inject a fake location into the emulator, and warns this does not replace real-device testing.

**Gap:** no per-screenshot `adb emu geo fix` command log was found in the run folder, so the exact injected coordinates/commands for each individual image are not independently verified here. The "Synthetic GPS" claim rests on the capture-run scripts' captions and the documented emulator workflow.

## Limits — what these images do NOT prove

These are **real UI captures of the v0.7.0 build on an Android emulator with simulated location and local demo music**. They are **not** real-riding footage, real-GPS data, or real-device validation. They do not verify physical-device behaviour, real GPS accuracy, SMS/calls/intercom, Doze/OEM behaviour, or any store/release gate. `docs/RELEASE_READINESS.md` (top, 2026-11-18 section) still lists physical device / real GPS / real riding as **UNVERIFIED** and the public 1.0 decision as **NO-GO**; no release, tag or APK was changed by adding these images.

No v0.7.0 call screen was available in the capture set, so none is included.

## Localisation note

The screens contain Turkish UI strings (for example "Başlat", "Bitir", "km/sa", "GPS iyi", "Mavi Saat"). The images are therefore not linguistically neutral: they are shipped in both locale folders because the same real captures were used for the v0.7.0 launch assets, but reviewers should expect Turkish text in `en-US` screenshots.

## Icon

`icon.png` in both locales is an unmodified copy of `docs/logo.png`, which was already a square **512×512 PNG**. No upscaling or conversion was needed.
