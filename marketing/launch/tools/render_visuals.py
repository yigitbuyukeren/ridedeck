#!/usr/bin/env python3
"""Render RideDeck launch visuals from the tracked v0.7.0 emulator screenshots.

No UI is invented: every screenshot is read from docs/screenshots/v0.7.0/.
Text, panels and captions are added around those captures. Run from the repo root:

    python3 marketing/launch/tools/render_visuals.py
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "docs" / "screenshots" / "v0.7.0"
OUT = ROOT / "marketing" / "launch" / "visuals"
OUT.mkdir(parents=True, exist_ok=True)

FONT_PATH = "/usr/share/fonts/inter/Inter.ttc"
FONT_INDEX = {
    "regular": 0,
    "medium": 10,
    "semibold": 12,
    "bold": 14,
    "extrabold": 16,
    "black": 1,
    "display_bold": 32,
    "display_extrabold": 34,
}

BG_TOP = (8, 13, 18)
BG_BOTTOM = (16, 28, 38)
PANEL = (18, 29, 39)
TEXT = (244, 247, 250)
MUTED = (157, 174, 188)
DIM = (117, 135, 150)
ORANGE = (232, 72, 12)
BLUE = (47, 155, 255)
GREEN = (61, 220, 132)


def font(size: int, weight: str = "regular") -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT_PATH, size, index=FONT_INDEX[weight])


def gradient(size, top=BG_TOP, bottom=BG_BOTTOM, horizontal=False):
    w, h = size
    if horizontal:
        base = Image.new("RGB", (w, 1))
        for x in range(w):
            t = x / max(1, w - 1)
            base.putpixel((x, 0), tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)))
        return base.resize(size)
    base = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        base.putpixel((0, y), tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)))
    return base.resize(size)


def add_glow(canvas, xy, radius, color, alpha=70, blur=120):
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x, y = xy
    d.ellipse([x - radius, y - radius, x + radius, y + radius], fill=(*color, alpha))
    layer = layer.filter(ImageFilter.GaussianBlur(blur))
    canvas.alpha_composite(layer)


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return mask


def load_shot(name: str, width: int) -> Image.Image:
    im = Image.open(SRC / name).convert("RGB")
    height = round(im.height * width / im.width)
    return im.resize((width, height), Image.Resampling.LANCZOS)


def paste_shot(canvas, name, x, y, width, radius=22, shadow=True):
    shot = load_shot(name, width)
    if shadow:
        shadow_layer = Image.new("RGBA", (shot.width + 100, shot.height + 100), (0, 0, 0, 0))
        sd = ImageDraw.Draw(shadow_layer)
        sd.rounded_rectangle([50, 54, 50 + shot.width, 54 + shot.height], radius=radius, fill=(0, 0, 0, 170))
        shadow_layer = shadow_layer.filter(ImageFilter.GaussianBlur(26))
        canvas.alpha_composite(shadow_layer, (x - 50, y - 50))
    shot = shot.convert("RGBA")
    shot.putalpha(rounded_mask(shot.size, radius))
    canvas.alpha_composite(shot, (x, y))
    return shot.size


def add_pill(canvas, xy, label, fg=TEXT, bg=(8, 13, 18, 185), size=15, pad_x=14, pad_y=8, weight="semibold"):
    draw = ImageDraw.Draw(canvas)
    f = font(size, weight)
    box = draw.textbbox((0, 0), label, font=f)
    w = box[2] - box[0] + 2 * pad_x
    h = box[3] - box[1] + 2 * pad_y
    x, y = xy
    draw.rounded_rectangle([x, y, x + w, y + h], radius=h // 2, fill=bg)
    draw.text((x + pad_x, y + pad_y - box[1]), label, font=f, fill=fg)
    return w, h


def draw_text(canvas, xy, text, f, fill=TEXT, spacing=10, anchor=None):
    ImageDraw.Draw(canvas).multiline_text(xy, text, font=f, fill=fill, spacing=spacing, anchor=anchor)


def footer(canvas, text, y=None, x=64, size=16):
    if y is None:
        y = canvas.height - 52
    draw_text(canvas, (x, y), text, font(size), DIM)


def new_canvas(size, glow=True):
    canvas = gradient(size).convert("RGBA")
    if glow:
        add_glow(canvas, (size[0] * 0.12, size[1] * 0.18), 280, ORANGE, alpha=42, blur=150)
        add_glow(canvas, (size[0] * 0.82, size[1] * 0.82), 330, BLUE, alpha=38, blur=170)
    return canvas


def render_hero():
    w, h = 1600, 900
    c = new_canvas((w, h))
    d = ImageDraw.Draw(c)

    # left rail
    d.rounded_rectangle([88, 112, 148, 120], radius=4, fill=ORANGE)
    add_pill(c, (88, 148), "OPEN SOURCE  ·  ANDROID  ·  v0.7.0", fg=(255, 184, 152), bg=(28, 20, 18, 210), size=16)
    draw_text(c, (88, 222), "RideDeck", font(126, "display_extrabold"), TEXT)
    draw_text(c, (88, 390), "A motorcycle\ndashboard for\nyour Android phone.", font(48, "semibold"), TEXT, spacing=8)
    draw_text(
        c,
        (88, 620),
        "GPS speed, voice navigation, music and trips\nin one landscape app.",
        font(25),
        MUTED,
        spacing=8,
    )
    add_pill(c, (88, 746), "FREE APK", fg=TEXT, bg=(232, 72, 12, 235), size=17)
    draw_text(c, (88, 820), "github.com/yigitbuyukeren/ridedeck", font(19, "medium"), DIM)

    add_pill(c, (680, 140), "ACTUAL v0.7.0 CAPTURE  ·  TURKISH UI  ·  ENGLISH AVAILABLE", fg=TEXT, bg=(8, 13, 18, 210), size=13)
    paste_shot(c, "dashboard-dark.png", 680, 190, 850, radius=24)
    footer(c, "Android emulator · simulated GPS", y=856, x=680)
    c.convert("RGB").save(OUT / "hero-1600x900.png", optimize=True)


def render_reddit(name, headline, subline, shot, accent=ORANGE):
    w, h = 1200, 630
    c = new_canvas((w, h))
    d = ImageDraw.Draw(c)

    d.rounded_rectangle([64, 82, 110, 89], radius=4, fill=accent)
    add_pill(c, (64, 116), "OPEN SOURCE  ·  ANDROID  ·  v0.7.0", fg=(255, 184, 152), bg=(28, 20, 18, 210), size=14)
    draw_text(c, (64, 176), "RideDeck", font(88, "display_extrabold"), TEXT)
    draw_text(c, (64, 292), headline, font(38, "semibold"), TEXT, spacing=7)
    draw_text(c, (64, 424), subline, font(22), MUTED, spacing=7)
    add_pill(c, (64, 512), "FREE APK  ·  APACHE 2.0", fg=TEXT, bg=(232, 72, 12, 235), size=15)
    draw_text(c, (64, 575), "github.com/yigitbuyukeren/ridedeck", font(16, "medium"), DIM)

    paste_shot(c, shot, 580, 92, 560, radius=20)
    footer(c, "Android emulator · simulated GPS · Turkish UI; English available", y=590, x=580, size=13)
    c.convert("RGB").save(OUT / name, optimize=True)


def render_dev_cover():
    w, h = 1000, 420
    c = new_canvas((w, h))
    d = ImageDraw.Draw(c)

    d.rounded_rectangle([56, 56, 98, 63], radius=3, fill=ORANGE)
    draw_text(c, (56, 82), "BUILD NOTES  ·  ANDROID", font(14, "semibold"), (255, 174, 139))
    draw_text(c, (56, 126), "What belongs on a\nmotorcycle dashboard?", font(42, "bold"), TEXT, spacing=4)
    draw_text(c, (56, 252), "Design decisions from building RideDeck\nwith Kotlin and Jetpack Compose", font(19), MUTED, spacing=5)
    add_pill(c, (56, 334), "RideDeck  ·  v0.7.0", fg=TEXT, bg=(232, 72, 12, 225), size=14)

    paste_shot(c, "route.png", 610, 48, 340, radius=16)
    footer(c, "Emulator · simulated GPS · Turkish UI; English available", y=382, x=56, size=13)
    c.convert("RGB").save(OUT / "dev-cover-1000x420.png", optimize=True)


def render_x_card():
    w, h = 1600, 900
    shot = Image.open(SRC / "navigation.png").convert("RGB")
    if shot.size != (w, h):
        shot = ImageOps.fit(shot, (w, h), Image.Resampling.LANCZOS)
    c = shot.convert("RGBA")

    # A translucent panel gives the copy a stable contrast against the light map.
    panel = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    pd = ImageDraw.Draw(panel)
    pd.rounded_rectangle([60, 500, 930, 860], radius=28, fill=(7, 12, 17, 252))
    c.alpha_composite(panel)

    add_pill(c, (92, 64), "RIDEDECK  ·  OPEN SOURCE  ·  ANDROID", fg=TEXT, bg=(8, 13, 18, 210), size=15)
    draw_text(c, (92, 548), "One landscape screen\nfor the ride.", font(60, "display_extrabold"), TEXT, spacing=4)
    draw_text(c, (92, 706), "GPS speed  ·  voice navigation  ·  music", font(25, "medium"), (225, 235, 242))
    draw_text(c, (92, 754), "Free APK  ·  Apache 2.0  ·  v0.7.0", font(20, "semibold"), (255, 174, 139))
    footer(c, "Android emulator capture · simulated GPS", y=812, x=92, size=14)
    add_pill(c, (1250, 798), "TURKISH UI · ENGLISH AVAILABLE", fg=TEXT, bg=(8, 13, 18, 220), size=13)
    c.convert("RGB").save(OUT / "x-card-1600x900.png", optimize=True)


def render_feature_grid():
    w, h = 1600, 900
    c = new_canvas((w, h))
    d = ImageDraw.Draw(c)

    draw_text(c, (64, 54), "Four jobs, one dashboard.", font(54, "display_extrabold"), TEXT)
    draw_text(c, (64, 124), "Actual v0.7.0 Android emulator captures with simulated GPS.", font(21), MUTED)
    add_pill(c, (64, 170), "TURKISH UI · ENGLISH AVAILABLE", fg=TEXT, bg=(28, 20, 18, 210), size=13)

    cards = [
        ("dashboard-dark.png", "Dashboard", "Speed, weather, music"),
        ("route.png", "Route preview", "Motorcycle route + stops"),
        ("navigation.png", "Guidance", "Next turn and arrival"),
        ("music.png", "Music", "Media controls"),
    ]
    card_w = 720
    gap = 26
    left = (w - (2 * card_w + gap)) // 2
    top = 206
    row_gap = 18
    for i, (shot, title, sub) in enumerate(cards):
        col, row = i % 2, i // 2
        x = left + col * (card_w + gap)
        y = top + row * (299 + row_gap)
        paste_shot(c, shot, x, y, 500, radius=14)
        draw_text(c, (x + 526, y + 26), title, font(30, "semibold"), TEXT)
        draw_text(c, (x + 526, y + 76), sub, font(19), MUTED, spacing=4)

    d.rounded_rectangle([64, 812, 1536, 814], radius=1, fill=(42, 58, 71))
    draw_text(c, (64, 838), "A motorcycle-first Android dashboard. Not a CarPlay or Android Auto integration.", font(22, "medium"), (225, 235, 242))
    footer(c, "Emulator captures · simulated GPS · motorcycle-only today; other vehicle types are a long-term goal, not a shipped feature.", y=872, x=64, size=14)
    c.convert("RGB").save(OUT / "feature-grid-1600x900.png", optimize=True)


if __name__ == "__main__":
    render_hero()
    render_reddit(
        "reddit-sideproject-1200x630.png",
        "One landscape screen for\nthe motorcycle ride.",
        "GPS speed  ·  voice navigation  ·  music  ·  trips",
        "dashboard-dark.png",
    )
    render_reddit(
        "reddit-imadethis-1200x630.png",
        "Built around one glance,\nnot another app drawer.",
        "An open-source Android dashboard,\nmade for a phone on the handlebars.",
        "route.png",
        accent=BLUE,
    )
    render_dev_cover()
    render_x_card()
    render_feature_grid()
    print("rendered:", *sorted(p.name for p in OUT.glob("*.png")), sep="\n  ")
