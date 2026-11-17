#!/usr/bin/env python3
"""Summarize Gradle JUnit XML reports and fail on missing or red results.

Usage: test-report-summary.py RESULT_DIR [RESULT_DIR ...]

Each RESULT_DIR is one Gradle test task output, for example
app/build/test-results/testDebugUnitTest. The script exits non-zero when a
directory has no XML report, a report has no tests, or any test failed/errored.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET


def summarize(directory):
    files = sorted(glob.glob(os.path.join(directory, "**", "TEST-*.xml"), recursive=True))
    if not files:
        print(f"FAIL: {directory}: no JUnit XML report (*/TEST-*.xml)")
        return None

    tests = failures = errors = skipped = 0
    seconds = 0.0
    bad = []
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as error:
            print(f"FAIL: {path}: {error}")
            return None
        suites = [root] if root.tag == "testsuite" else root.findall(".//testsuite")
        for suite in suites:
            tests += int(suite.get("tests", "0"))
            suite_failures = int(suite.get("failures", "0"))
            suite_errors = int(suite.get("errors", "0"))
            failures += suite_failures
            errors += suite_errors
            skipped += int(suite.get("skipped", "0"))
            seconds += float(suite.get("time", "0"))
            if suite_failures or suite_errors:
                bad.append(suite.get("name") or path)

    if tests == 0:
        print(f"FAIL: {directory}: reports contain 0 tests")
        return None

    print(
        f"{directory}: {tests} tests, {failures} failures, {errors} errors, "
        f"{skipped} skipped, {seconds:.2f}s"
    )
    for name in bad:
        print(f"  red: {name}")
    result = {
        "tests": tests,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
    }
    if failures or errors:
        return None
    return result


def main(argv):
    if not argv:
        print(__doc__.strip())
        return 2

    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    failed = False
    for directory in argv:
        result = summarize(directory)
        if result is None:
            failed = True
            continue
        for key in totals:
            totals[key] += result[key]

    if failed:
        print("FAIL: test reports are missing or not green")
        return 1
    print(
        f"PASS: {totals['tests']} tests, {totals['failures']} failures, "
        f"{totals['errors']} errors, {totals['skipped']} skipped"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
