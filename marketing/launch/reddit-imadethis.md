# r/IMadeThis — original post

**Suggested flair:** I Made This (use the live community’s current flair; do not guess if it has changed).

**Title:** I made a motorcycle dashboard that turns an Android phone into a one-screen cockpit

**Body:**

I made RideDeck: a landscape Android dashboard for motorcycles.

The design question I kept returning to was simple: **what deserves a place on the first screen?** I ended up with speed as the largest element, the next turn and map on the right, and music controls underneath. The left dock holds navigation, music, phone and settings. The UI uses large touch targets, high-contrast day/night themes, and a layout that stays put when the phone is rotated 180° in its mount.

A few decisions shaped the build:

- When GPS goes stale, the speed shows `--` instead of pretending the bike stopped at 0.
- The gauge uses a filtered display speed, while recording and safety logic keep the raw GPS measurement.
- Text entry and settings lock while moving; setup is a parked activity.
- Trips and settings stay on the device. There is no account or RideDeck server.
- English and Turkish are supported. In v0.7.0, the app language can change without restarting; the TTS voice and route instructions follow the new language. I have not listened to the real TTS output in both languages yet.

It is **not** CarPlay or Android Auto. It runs on the phone’s own screen and does not connect to either system. It is motorcycle-only for now; other vehicle types are a long-term goal, not something the current app supports.

v0.7.0 is a free, open-source (Apache 2.0) APK on GitHub, Android 8+, ARM. Navigation, search and weather require internet. There is no offline routing, and new offline map downloads are paused. SOS/crash detection is experimental, default-off and not field-tested. The screenshots are emulator captures with simulated GPS and the Turkish UI (English is available in the app), not real riding or a physical device.

If you’ve built a glanceable interface, I’d especially like feedback on the information hierarchy: what would you cut first, and why?

https://github.com/yigitbuyukeren/ridedeck

**Image:** `visuals/reddit-imadethis-1200x630.png`

**Image alt text:** A blue-and-dark promotional card with the RideDeck wordmark and a route-planning screen from the v0.7.0 Android emulator. The screen shows a destination card, “Avoid highways” and “Avoid tolls” options, a blue route on a map, and a start button. The caption says the capture is from an emulator with simulated GPS and a Turkish UI, with English available.

**Posting notes:**

- This is intentionally a different angle from the r/SideProject draft: it leads with the design constraint and specific implementation decisions rather than the side-project story.
- Do not paste the same title or body into both communities.
- The emulator label is part of the claim. Keep it in the post, not only in the image.
- Do not describe the app as a safety device, a CarPlay/Android Auto product, or a replacement for navigation hardware.
- If the community requires a text-only post, use the image as a link inside the body only if the rules allow it.
