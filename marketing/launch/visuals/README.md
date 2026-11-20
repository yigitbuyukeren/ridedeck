# RideDeck launch visuals

All images are composed from the tracked **v0.7.0 Android-emulator captures** in `docs/screenshots/v0.7.0/`. The app UI is Turkish; the launch captions are English. The app screens, map data, speed readouts and navigation instructions come from those captures; only the captions and layout panels were added.

## Assets

| File | Size | Source capture | Intended use |
|---|---:|---|---|
| `hero-1600x900.png` | 1600×900 | `dashboard-dark.png` | General hero, X post 1, repository/social preview |
| `reddit-sideproject-1200x630.png` | 1200×630 | `dashboard-dark.png` | r/SideProject image post |
| `reddit-imadethis-1200x630.png` | 1200×630 | `route.png` | r/IMadeThis image post |
| `dev-cover-1000x420.png` | 1000×420 | `route.png` | DEV Community cover |
| `x-card-1600x900.png` | 1600×900 | `navigation.png` | X navigation post |
| `feature-grid-1600x900.png` | 1600×900 | `dashboard-dark.png`, `route.png`, `navigation.png`, `music.png` | Reddit/DEV inline feature overview |

## Alt text

**hero-1600x900.png:** Dark RideDeck promotional card with the headline “A motorcycle dashboard for your Android phone.” On the right is an Android-emulator dashboard screenshot with a large GPS speed readout, a map card, music controls and a side dock. The caption labels the capture as v0.7.0, emulator, simulated GPS and Turkish UI, with English available.

**reddit-sideproject-1200x630.png:** RideDeck card with the headline “One landscape screen for the motorcycle ride.” It shows a landscape Android dashboard with speed, map and music, plus a footer saying the capture is from an emulator with simulated GPS, Turkish UI and English available.

**reddit-imadethis-1200x630.png:** RideDeck card with the headline “Built around one glance, not another app drawer.” It shows the route-planning screen with a destination card, route options and a blue route line on a map. The footer labels the capture as emulator, simulated GPS, Turkish UI and English available.

**dev-cover-1000x420.png:** DEV article cover titled “What belongs on a motorcycle dashboard?” with a route-planning screenshot from the RideDeck v0.7.0 emulator. The footer labels the capture as emulator, simulated GPS, Turkish UI and English available.

**x-card-1600x900.png:** RideDeck navigation screen with a blue next-turn instruction and a blue route line on a light map. A dark panel carries the text “One landscape screen for the ride,” plus the emulator and simulated-GPS label.

**feature-grid-1600x900.png:** Four RideDeck screens in a two-by-two grid: dashboard, route preview, guidance and music. The footer says the app is a motorcycle-first Android dashboard, not a CarPlay or Android Auto integration, and that the captures are emulator/simulated-GPS images.

## Evidence labels

Every asset includes, in the image or its footer:

- **Android emulator**
- **simulated GPS**
- **Turkish UI; English available** (where applicable)

The app supports English and Turkish. The v0.7.0 captures happen to be Turkish; do not present them as English UI or as real-device/road evidence.

## Rebuilding

From the repository root:

```bash
python3 marketing/launch/tools/render_visuals.py
```

The renderer needs Python 3, Pillow and the Inter font family (it uses the system Inter collection). It reads only `docs/screenshots/v0.7.0/` and writes into this folder. It does not modify the app, release assets or screenshots.
