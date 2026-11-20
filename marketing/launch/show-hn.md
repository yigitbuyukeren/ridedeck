# Show HN — RideDeck

> **Author note:** Hacker News explicitly prohibits generated or AI-edited submission text. Use this file as a factual outline and write the final title/body yourself, in your own words. Do not paste this as-is.

## Submission

**Title:** Show HN: RideDeck – Open-source motorcycle dashboard for Android

**URL:** https://github.com/yigitbuyukeren/ridedeck

The repository is the better submission target than a bare APK URL: a reader can see the source, licence, README and release link before installing. The v0.7.0 release page is a reasonable alternative if you want the download and asset metadata to be the first thing visible.

**Suggested first comment / context:**

RideDeck is a free, open-source Android app that turns a phone into a landscape motorcycle dashboard. It shows GPS speed, an OpenStreetMap/MapLibre map with Valhalla motorcycle routing and voice guidance, media controls, phone controls, and local trip history. There is no account and no RideDeck backend; routing, search and weather require internet.

I built it because I wanted one screen on the bike instead of switching between a map and a music app. It is standalone Android software. CarPlay and Android Auto were layout references, not integrations — RideDeck does not connect to either system or project to a head unit.

The current build is v0.7.0, a signed APK on GitHub Releases for Android 8+ (ARM), Apache 2.0. The source is in the repository, and the release page has the APK hash.

What is not there yet: real-device and real-riding validation are still open; SOS/crash detection is experimental, default-off and not field-tested; new offline map downloads are paused; routing is online-only; there is no live traffic or radar alerts. The screenshots are v0.7.0 emulator captures with simulated GPS and a Turkish UI; English is available in the app. The app is motorcycle-only today; other vehicle types are a long-term goal, not a shipped feature.

I would appreciate feedback on the landscape layout and the Android permission/role handling. What would you cut from a glanceable dashboard, and what would you want to see first?

## What to verify before submitting

- [ ] The repository link opens logged out and the README’s English section is first.
- [ ] The v0.7.0 release page opens and the signed APK downloads from the official link.
- [ ] The APK installs through the normal Android unknown-source flow on a clean test device; the app opens without an account or a build step.
- [ ] The permissions requested by the features you demonstrate are explained accurately.
- [ ] The screenshots shown in the thread are v0.7.0 emulator captures and carry the simulated-GPS label.
- [ ] The author is available that day to answer technical questions and reproduce device/permission reports.
- [ ] No vote/comment solicitation, no cross-posted identical launch text, and no AI-generated HN copy.

## Why this angle

- Show HN readers reward a concrete description, a real engineering decision, and a project they can try. The title names the form factor (Android) and the category (motorcycle dashboard) without claiming a platform integration.
- The body states the differentiation (“runs on the phone, not CarPlay/Android Auto”) before a reader has to ask.
- The limitations are part of the pitch, not a footnote. That is especially important for an APK-only, pre-1.0 project with an experimental SOS feature.
- Do not append `[video]` unless the submission URL itself is the video. If you submit the repository and put a video in a comment, the link stays the repository.
