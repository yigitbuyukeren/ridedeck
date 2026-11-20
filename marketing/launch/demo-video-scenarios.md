# Short demo video scenarios

All three scenarios are **parked/bench demos** built from the same v0.7.0 build and the tracked v0.7.0 emulator imagery. They are not real-riding, physical-device or safety tests.

## Capture rules (apply to all three)

- Use the Android emulator with simulated GPS. Set the emulator to a 16:9 landscape resolution (1280×720 or 1920×1080) and 30 fps.
- Record the app screen with `adb shell screenrecord` or an emulator recorder. Do not film a rider touching the phone on a moving motorcycle.
- Keep the UI in Turkish if you are reusing the current v0.7.0 captures; add an English caption “Turkish UI · English available”. If you capture a fresh English build, say “English UI” and keep the emulator/simulated-GPS label.
- Keep the OpenStreetMap/OpenFreeMap attribution visible. Do not crop it out.
- Remove or avoid personal data: contacts, real phone numbers, notification text, real home/work addresses, licence plates and account details.
- Do not imply that SOS is tested, reliable or a safety guarantee. Do not stage a fake crash or a fake emergency response.
- Export a silent-first video with burned-in captions; viewers often watch muted. Keep text inside the safe area so mobile crops do not cut it.
- Add a final card: “RideDeck v0.7.0 · Android emulator · simulated GPS · pre-1.0, real-device validation open”.

## Scenario 1 — “One screen at a standstill” (15–20 seconds)

**Goal:** Show the home-screen hierarchy in one glance: speed, next turn/map, music, dock.

**Suggested use:** X post 1, Reddit image/video, DEV inline demo.

**Shot list:**

1. **0–3 s:** Start on the dark dashboard at a simulated standstill or low speed. Hold still so the viewer can read the speed, clock, battery, temperature and the right-hand cards.
2. **3–8 s:** Slowly move the cursor/highlight from the speed card to the next-turn card, then to the music card. Do not open a menu.
3. **8–12 s:** Tap the theme switch (or show the light dashboard capture) to demonstrate day/night appearance. Keep it a single stationary interaction.
4. **12–15 s:** Return to the dashboard and end on the full landscape screen.

**On-screen text:**

- 0–3 s: “One landscape screen.”
- 3–8 s: “Speed · next turn · music.”
- 8–12 s: “Day and night themes.”
- 12–15 s: “Android emulator · simulated GPS · Turkish UI; English available.”

**Voiceover (optional):** “This is RideDeck on an Android emulator. Speed is the main card, the next turn and map are on the right, and music stays within reach. The screenshots use simulated GPS; this is not a road test.”

**Acceptance check:** A viewer can identify the three primary jobs without opening a menu, and the emulator/simulated-GPS label is readable at mobile size.

## Scenario 2 — “Plan a route, then start guidance” (25–35 seconds)

**Goal:** Show the online motorcycle-routing flow and the transition from route preview to active guidance.

**Suggested use:** Reddit text post, DEV article, Show HN comment if a video is requested.

**Shot list:**

1. **0–4 s:** Start on the dashboard, then open the navigation screen. Show the map and the destination card.
2. **4–10 s:** Show the destination (“Emirgan Mahallesi” in the existing capture), distance/time and the “Avoid highways” / “Avoid tolls” options. Do not type a real address; use a saved/demo destination.
3. **10–16 s:** Show the blue route line and the “Start” action. State that routing requires internet.
4. **16–24 s:** Start guidance. Show the next-turn card, remaining distance, arrival time and the map. Let the simulated GPS move a short distance so the progress line advances.
5. **24–30 s:** End on the navigation screen, then cut to the final label card.

**On-screen text:**

- 0–4 s: “Plan a motorcycle route.”
- 4–10 s: “Demo destination and route options.”
- 10–16 s: “Routing requires internet.”
- 16–24 s: “Guidance with next turn and arrival.”
- 24–30 s: “Android emulator · simulated GPS · not a real ride.”

**Voiceover (optional):** “RideDeck uses OpenStreetMap and MapLibre for the map and Valhalla for motorcycle routing. I’m starting a demo route on the emulator with simulated GPS. The route and search need a network connection; there is no offline routing.”

**Acceptance check:** The video makes the online-only routing limitation clear and does not present the simulated movement as a real road test.

## Scenario 3 — “Glanceable controls: music and the dock” (18–25 seconds)

**Goal:** Show that media and the main modes live in the same landscape shell without turning the dashboard into a settings app.

**Suggested use:** X post 5, Reddit comment reply, DEV inline demo.

**Shot list:**

1. **0–4 s:** Start on the dashboard with the music card visible. Show the track title, progress and transport controls.
2. **4–8 s:** Tap the music dock icon once. Show the full music screen with cover art and controls. Do not show a real personal library or notification content.
3. **8–13 s:** Return to the dashboard and show the dock’s navigation, music, phone and settings icons. Keep the camera/UI still.
4. **13–18 s:** Open the phone screen briefly to show the keypad layout; do not dial a real number. Return to the dashboard.
5. **18–22 s:** End on the dashboard with the final label card.

**On-screen text:**

- 0–4 s: “Music without leaving the dashboard.”
- 4–8 s: “Media controls on the same screen.”
- 8–13 s: “Navigation · music · phone · settings.”
- 13–18 s: “Setup stays parked.”
- 18–22 s: “Android emulator · simulated GPS · Turkish UI; English available.”

**Voiceover (optional):** “The dock is deliberately small: navigation, music, phone and settings. Music controls stay on the dashboard, and setup screens are for when the bike is parked. This is an emulator demo, not a riding test.”

**Acceptance check:** The video shows one clear interaction per shot, keeps the dock legible, and never suggests the rider is operating the phone while moving.

## Export checklist

- [ ] 16:9, 30 fps, H.264 MP4; keep the file small enough for the target platform.
- [ ] Burned-in English captions; no critical information only in audio.
- [ ] First frame is readable without sound.
- [ ] Emulator/simulated-GPS label is visible in the video and in the post copy.
- [ ] No personal data, no real dialing, no real SMS, no staged crash.
- [ ] Map attribution preserved.
- [ ] Final card includes version, emulator label and “real-device validation open”.
