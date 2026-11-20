# r/SideProject — original post

**Suggested flair:** Side Project / Show & Tell (use the flair the live community rules require).

**Title:** I built an open-source Android dashboard for my motorcycle — one landscape screen for speed, route and music

**Body:**

I’m the developer of RideDeck, a side project that started with a small annoyance: my phone mount had turned into a stack of apps. One for speed, one for navigation, one for music. I wanted a single landscape screen I could set up before a ride and then mostly leave alone.

RideDeck is a standalone Android app for motorcycles. The current build combines:

- GPS speed, clock, battery and phone temperature
- an OpenStreetMap/MapLibre map with Valhalla motorcycle routing and voice guidance (online)
- media controls and permission-dependent phone controls
- local trip history
- English and Turkish UI

It is **not** a CarPlay or Android Auto integration. Those layouts were the inspiration; RideDeck runs on the phone itself and does not project to a head unit. It is motorcycle-only today. Other vehicle types are a long-term goal, not a shipped feature.

The current release is v0.7.0: a free, signed GitHub APK for Android 8+ (ARM), Apache 2.0. There is no account and no RideDeck backend. Routing, search and weather need internet; offline routing is not available, and new offline map downloads are paused.

The screenshots are from the Android emulator with simulated GPS and the Turkish UI (English is available in the app), not a real ride or a physical device. Real-device and real-riding validation is still open. SOS/crash detection is experimental and default-off, so I would not present this as a safety product.

What I’m trying to work out now is the home-screen balance. Speed gets the center, the route sits on the right, and music is at the bottom. If you already use a phone on a bike, what would you remove, and what would you make bigger?

Repo and v0.7.0 release: https://github.com/yigitbuyukeren/ridedeck

**Image:** `visuals/reddit-sideproject-1200x630.png`

**Image alt text:** A dark promotional card with the RideDeck logo and an Android-emulator screenshot in landscape. The screenshot shows a large GPS speed readout, a map with a blue route, music controls, and a side dock. The caption says the capture is from an emulator with simulated GPS and a Turkish UI, with English available.

**Posting notes:**

- Disclose that you are the developer in the first sentence, as above.
- Keep one direct repository link in the body. Do not put the link in the title if the community rules prefer a text post.
- Use the 1200×630 image as the single post image. It is readable at mobile feed width, but check the crop in the posting UI.
- Do not cross-post this exact text to r/IMadeThis. Use `reddit-imadethis.md` for the different angle.
- If the community requires a “no self-promotion” flair or a weekly thread, follow that instead of posting a standalone thread.
- Do not ask for votes, stars or a specific rank. Ask one concrete design question and answer replies as yourself.
