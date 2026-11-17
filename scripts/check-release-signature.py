#!/usr/bin/env python3
"""Verify the release APK signature when a local keystore is configured.

CI has no keystore.properties and builds an unsigned release APK/AAB, so this
check skips cleanly there. Locally it proves the built app-release.apk really
carries a signature before any manual distribution.
"""

import glob
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KEYSTORE_PROPERTIES = os.path.join(ROOT, "keystore.properties")
APK = os.path.join(ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")


def find_apksigner():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "apksigner")))
        if candidates:
            return candidates[-1]
    return shutil.which("apksigner")


def main():
    if not os.path.exists(KEYSTORE_PROPERTIES):
        print("SKIP: keystore.properties is absent; release output is unsigned by design.")
        return 0

    if not os.path.exists(APK):
        print(f"FAIL: {APK} is missing; the signed release APK was not produced")
        return 1

    apksigner = find_apksigner()
    if not apksigner:
        print("FAIL: apksigner not found; cannot verify the release signature")
        return 1

    result = subprocess.run(
        [apksigner, "verify", "--verbose", "--print-certs", APK],
        text=True,
        capture_output=True,
    )
    sys.stdout.write(result.stdout)
    sys.stderr.write(result.stderr)
    if result.returncode != 0:
        print("FAIL: release APK signature verification failed")
        return 1
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in result.stdout:
        print("FAIL: release APK is not v2-signed")
        return 1
    print("PASS: release APK signature verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
