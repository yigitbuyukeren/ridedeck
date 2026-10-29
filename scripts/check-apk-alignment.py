#!/usr/bin/env python3
"""Check 64-bit ELF LOAD alignment in APKs; run SDK zipalign separately for ZIP alignment."""

import struct
import sys
import zipfile


def check(apk):
    checked = 0
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not name.startswith(("lib/arm64-v8a/", "lib/x86_64/")) or not name.endswith(".so"):
                continue
            data = archive.read(name)
            if data[:6] != b"\x7fELF\x02\x01":
                raise ValueError(f"{name}: expected a little-endian ELF64 library")
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, count = struct.unpack_from("<HH", data, 54)
            if size < 56 or not count:
                raise ValueError(f"{name}: invalid program headers")
            loads = []
            for index in range(count):
                header = struct.unpack_from("<IIQQQQQQ", data, offset + index * size)
                if header[0] == 1:  # PT_LOAD
                    alignment = header[7]
                    if alignment < 16384 or alignment & (alignment - 1) or (header[2] - header[3]) % alignment:
                        raise ValueError(f"{name}: LOAD is not 16 KB aligned (alignment={alignment})")
                    loads.append(alignment)
            if not loads:
                raise ValueError(f"{name}: no LOAD segments")
            checked += 1
            print(f"{apk}: {name}: PASS (LOAD alignments {loads})")
    if not checked:
        raise ValueError("no 64-bit native libraries found; check the release ABI configuration")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("Usage: check-apk-alignment.py APK [APK ...]")
    try:
        for path in sys.argv[1:]:
            check(path)
    except (OSError, ValueError, struct.error, zipfile.BadZipFile) as error:
        sys.exit(f"FAIL: {error}")
