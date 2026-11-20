# RideDeck international launch kit

Status: **review-ready draft — the social posts in this folder have not been published.**

Product baseline: **RideDeck v0.7.0**, `versionCode 8`, signed GitHub APK, Android 8.0+ (API 26), ARM (`arm64-v8a` / `armeabi-v7a`). Source: Apache 2.0. Latest public facts checked against the v0.7.0 release and the English-first `main` README on 20 November 2026.

## What is in this folder

| File | Use |
|---|---|
| `reddit-sideproject.md` | Original English post for r/SideProject, plus title, image, alt text and posting notes |
| `reddit-imadethis.md` | A different English post for r/IMadeThis; do not cross-post the same wording |
| `show-hn.md` | Show HN title, body/first comment, link choice and author checklist |
| `dev-article.md` | Technical DEV Community article with build decisions, code excerpt, limits and cover image |
| `x-posts.md` | Five standalone English X posts, character counts and media suggestions |
| `demo-video-scenarios.md` | Three short demo scenarios, shot lists, captions and capture rules |
| `visuals/` | Six promotional images rendered from the tracked v0.7.0 emulator screenshots |
| `visuals/README.md` | Asset map, source screenshots, dimensions and alt text |
| `tools/render_visuals.py` | Reproducible Pillow renderer for the `visuals/` PNGs |

## Approved product framing

RideDeck is a **free, open-source, motorcycle-focused Android dashboard**. It is inspired by the glanceable layout idea of CarPlay and Android Auto, but it is **not** a CarPlay or Android Auto integration and does not project to a head unit. It runs on the phone itself. It is motorcycle-only today; other vehicle types are a long-term goal, not a shipped feature.

What the current build actually offers:

- Landscape dashboard with GPS speed, clock, battery and phone temperature.
- OpenStreetMap-based map (MapLibre/OpenFreeMap) with Valhalla motorcycle routing, saved places, waypoints, fuel stops and voice guidance. Routing, search and weather require internet.
- Media controls, optional message readout and permission/role-dependent phone controls.
- Local trip recording and history; no RideDeck account or RideDeck backend.
- English and Turkish UI. In v0.7.0, the app language can change without restarting the app; unsupported device languages fall back to English.
- Optional experimental SOS/crash detection. It is default-off, not field-tested, does not guarantee SMS delivery, and is not an emergency-service or safety-equipment substitute.

## Claims to avoid

Do **not** say or imply:

- “CarPlay for motorcycles”, “Android Auto alternative”, “works with CarPlay/Android Auto”, iOS support, head-unit projection or any Apple/Google affiliation.
- Google Play, F-Droid or IzzyOnDroid availability. The current install path is the signed GitHub APK.
- “1.0”, “production-ready safety device”, “prevents accidents”, “reduces distraction”, “tested on real rides”, “works on all Android phones”, or “offline navigation”.
- Live traffic, radar alerts, dashcam, intercom, OBD, GPX export, accounts, cloud sync or a RideDeck server.
- Universal vehicle support. The app is motorcycle-only today.
- Measured glove use, sunlight readability or battery/thermal performance. Real-device, real-riding, real SMS/call, lock-screen/Doze/OEM and physical 16 KB runtime checks remain open.

## Visual and evidence rules

- All screenshots in `visuals/` are built from **actual v0.7.0 Android-emulator captures** in `docs/screenshots/v0.7.0/`, with simulated GPS and the Turkish UI. The app screens, map data, speed readouts and navigation instructions come from those captures; only the English captions and layout panels were added.
- Every asset says “Android emulator · simulated GPS” and calls out the Turkish UI/English availability where it matters.
- If you replace the screenshots with new captures, keep the same labels and re-check the visible map attribution (`OpenFreeMap © OpenMapTiles · © OpenStreetMap`).
- Do not add personal data, real home/work locations, phone numbers, notification contents, licence plates or a rider interacting with the phone while moving.

## Platform rules that affect publication

- **Hacker News:** Show HN submissions and comments must be written by the author; HN’s rules and moderator guidance prohibit generated or AI-edited text. `show-hn.md` is a factual outline to rewrite in your own words, not paste-ready HN copy.
- **DEV Community:** the current AI guide permits disclosed, fact-checked assistance but says AI-generated content should not primarily promote a business, program or the author. Use `dev-article.md` as an editorial draft, rewrite it in your own voice, and follow the current disclosure rule if assistance was used.
- **Reddit:** rules differ by community and change. Check the live rules, account eligibility and flair immediately before posting. Do not post the same wording in multiple subreddits, ask for votes, or use alternate accounts. The two Reddit drafts deliberately take different angles.
- **X:** keep posts human and infrequent. Do not use engagement pods, automated replies, hashtag stuffing or identical copy. The five drafts are alternatives/sequence options, not a batch to post at once.

## Suggested order (manual, one channel at a time)

1. Pick one Reddit community that fits the account’s real history; post one draft, then stay to answer questions.
2. Publish the DEV article when there is time to reply to technical comments.
3. Run Show HN only when the author can be present for the thread and the APK link has been checked end-to-end on a clean device.
4. Use X as a short demo/feedback channel, not a broadcast loop.
5. Wait at least several days between major channels. A launch is not a countdown; do not repost a quiet thread unchanged.

## Canonical links

- Repository: https://github.com/yigitbuyukeren/ridedeck
- Stable release: https://github.com/yigitbuyukeren/ridedeck/releases/tag/v0.7.0
- APK: https://github.com/yigitbuyukeren/ridedeck/releases/download/v0.7.0/RideDeck-0.7.0.apk

Avoid sending launch traffic to the standalone website until its English/Turkish pages point at v0.7.0 instead of the older beta and match the current version/test facts.
