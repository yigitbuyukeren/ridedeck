#!/usr/bin/env bash
# Classify changed paths so CI can skip Gradle/emulator work for docs-only PRs.
#
# Usage: detect-changes.sh <base-ref> <head-ref>
#
# Prints GITHUB_OUTPUT-style key=value lines:
#   app=true      -> at least one changed path can affect the Android build/runtime
#   docs=true     -> at least one changed path is documentation/metadata
#
# The script fails safe: missing refs, a git error, or an empty/ambiguous diff
# all set app=true so a detection problem never silently skips tests.
set -euo pipefail

BASE="${1:-}"
HEAD="${2:-}"

app=false
docs=false

is_docs_path() {
  case "$1" in
    docs/*|fastlane/*|captures/*)
      return 0
      ;;
    *.md|LICENSE*|NOTICE*|README*|CONTRIBUTING*|CODE_OF_CONDUCT*|SECURITY*|SUPPORT*)
      return 0
      ;;
    scripts/check-fastlane-metadata.py)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

if [ -z "$BASE" ] || [ -z "$HEAD" ]; then
  echo "detect-changes: missing base/head ref; failing safe to app=true" >&2
  app=true
elif ! files="$(git diff --name-only "${BASE}...${HEAD}" 2>/dev/null)"; then
  echo "detect-changes: git diff failed; failing safe to app=true" >&2
  app=true
elif [ -z "$files" ]; then
  echo "detect-changes: empty diff; failing safe to app=true" >&2
  app=true
else
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    if is_docs_path "$file"; then
      docs=true
    else
      app=true
    fi
  done <<< "$files"
fi

echo "app=${app}"
echo "docs=${docs}"
