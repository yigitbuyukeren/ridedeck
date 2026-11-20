#!/usr/bin/env python3
"""Validate the fastlane metadata used for IzzyOnDroid / F-Droid distribution.

Run from anywhere:
    python3 scripts/check-fastlane-metadata.py

The checks are intentionally strict about structure and text limits so a broken
or partial metadata drop is caught by CI before it reaches a store repo.
No third-party Python packages are required; PNG dimensions are read directly
from the IHDR chunk.
"""

from __future__ import annotations

import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
META_ROOT = ROOT / "fastlane" / "metadata" / "android"
LOCALES = ("en-US", "tr-TR")
SHORT_LIMIT = 80
FULL_LIMIT = 4000
TITLE_LIMIT = 30
MIN_SCREENSHOTS = 3
SECRET_PATTERNS = (
    re.compile(r"BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY", re.I),
    re.compile(r"storePassword\s*=|keyPassword\s*=|keystore\.properties", re.I),
    re.compile(r"ghp_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}", re.I),
)

errors: list[str] = []
notes: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        fail(f"{rel(path)}: cannot read as UTF-8 ({exc})")
        return ""


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def png_size(path: Path) -> tuple[int, int] | None:
    """Return (width, height) from a valid PNG, or None if the file is not PNG."""
    try:
        with path.open("rb") as handle:
            header = handle.read(24)
    except OSError as exc:
        fail(f"{rel(path)}: cannot open ({exc})")
        return None
    if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        fail(f"{rel(path)}: not a valid PNG file")
        return None
    width, height = struct.unpack(">II", header[16:24])
    if width == 0 or height == 0:
        fail(f"{rel(path)}: PNG has zero dimension")
        return None
    return width, height


def android_version() -> tuple[int, str]:
    gradle = ROOT / "app" / "build.gradle.kts"
    if not gradle.is_file():
        fail("app/build.gradle.kts is missing (cannot derive versionCode/versionName)")
        return 0, ""
    text = read_text(gradle)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not code or not name:
        fail("app/build.gradle.kts: versionCode/versionName not found")
        return 0, ""
    return int(code.group(1)), name.group(1)


def check_text_file(path: Path, limit: int | None, single_line: bool = False) -> str:
    if not path.is_file():
        fail(f"{rel(path)}: missing")
        return ""
    text = read_text(path)
    stripped = text.strip()
    if not stripped:
        fail(f"{rel(path)}: empty")
        return ""
    if single_line and "\n" in stripped:
        fail(f"{rel(path)}: must be a single line")
    if limit is not None and len(stripped) > limit:
        fail(f"{rel(path)}: {len(stripped)} chars, limit is {limit}")
    for pattern in SECRET_PATTERNS:
        if pattern.search(text):
            fail(f"{rel(path)}: looks like secret material — do not ship")
    return stripped


def check_locale(locale: str, version_code: int, version_name: str) -> None:
    base = META_ROOT / locale
    if not base.is_dir():
        fail(f"{rel(base)}: locale directory missing")
        return

    title = check_text_file(base / "title.txt", TITLE_LIMIT, single_line=True)
    if title and "ridedeck" not in title.lower():
        fail(f"{rel(base / 'title.txt')}: expected the RideDeck app name")

    short = check_text_file(base / "short_description.txt", SHORT_LIMIT, single_line=True)
    full = check_text_file(base / "full_description.txt", FULL_LIMIT)
    if full and len(full.splitlines()) < 3:
        notes.append(f"{rel(base / 'full_description.txt')}: very short for a store listing")
    if full and not re.search(r"synthetic|simulated", full, re.I):
        fail(f"{rel(base / 'full_description.txt')}: must disclose synthetic/simulated GPS")
    if full and "gps" not in full.lower():
        fail(f"{rel(base / 'full_description.txt')}: must mention GPS in the disclosure")

    # fastlane changelog files are keyed by versionCode, not by version name.
    changelog = base / "changelogs" / f"{version_code}.txt"
    body = check_text_file(changelog, None)
    if body and version_name and version_name not in body:
        fail(f"{rel(changelog)}: does not mention versionName {version_name}")

    images = base / "images"
    if not images.is_dir():
        fail(f"{rel(images)}: images directory missing")
        return

    icon = images / "icon.png"
    if not icon.is_file():
        fail(f"{rel(icon)}: missing")
    else:
        size = png_size(icon)
        if size and (size[0] != size[1] or size[0] < 512):
            fail(f"{rel(icon)}: must be square and at least 512x512 (got {size[0]}x{size[1]})")

    shots_dir = images / "phoneScreenshots"
    if not shots_dir.is_dir():
        fail(f"{rel(shots_dir)}: missing")
        return
    shots = sorted(
        (p for p in shots_dir.iterdir() if p.is_file()),
        key=lambda p: int(p.stem) if p.stem.isdigit() else 10**6,
    )
    pngs = [p for p in shots if p.suffix.lower() == ".png"]
    others = [p for p in shots if p.suffix.lower() != ".png"]
    if others:
        fail(f"{rel(shots_dir)}: non-PNG files present: {', '.join(p.name for p in others)}")
    if len(pngs) < MIN_SCREENSHOTS:
        fail(f"{rel(shots_dir)}: need at least {MIN_SCREENSHOTS} screenshots, found {len(pngs)}")
    indices = []
    for shot in pngs:
        if not re.fullmatch(r"[1-9]\d*", shot.stem):
            fail(f"{rel(shot)}: screenshots must be named 1.png, 2.png, ...")
        else:
            indices.append(int(shot.stem))
        size = png_size(shot)
        if size and (size[0] < 320 or size[1] < 320):
            fail(f"{rel(shot)}: too small for a phone screenshot ({size[0]}x{size[1]})")
    if indices and sorted(indices) != list(range(1, len(indices) + 1)):
        fail(f"{rel(shots_dir)}: screenshot numbering has gaps: {sorted(indices)}")


def check_provenance() -> None:
    """Screenshots must be labelled as emulator captures with synthetic GPS."""
    path = META_ROOT / "en-US" / "images" / "SCREENSHOT-PROVENANCE.md"
    if not path.is_file():
        fail(f"{rel(path)}: missing (screenshot provenance must be documented)")
        return
    text = read_text(path).lower()
    if "synthetic" not in text and "simulated" not in text:
        fail(f"{rel(path)}: must state that location data was synthetic/simulated")
    if "gps" not in text:
        fail(f"{rel(path)}: must mention GPS explicitly")
    if "emulator" not in text:
        fail(f"{rel(path)}: must state that captures were made on an emulator")


def main() -> int:
    if not META_ROOT.is_dir():
        fail(f"{rel(META_ROOT)}: metadata root missing")
    else:
        version_code, version_name = android_version()
        if version_code:
            for locale in LOCALES:
                check_locale(locale, version_code, version_name)
        check_provenance()

    if notes:
        for note in notes:
            print(f"NOTE  {note}")
    if errors:
        for error in errors:
            print(f"FAIL  {error}", file=sys.stderr)
        print(f"\nfastlane metadata check FAILED ({len(errors)} problem(s))", file=sys.stderr)
        return 1
    print(
        "PASS  fastlane metadata check: "
        f"{len(LOCALES)} locales, versionCode/versionName text limits, "
        "changelog, icon and screenshots valid"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
