#!/usr/bin/env bash
set -euo pipefail

ROOT="${CORY_ROOT:-/workspace/Cory}"
REPORT="$ROOT/build-all-assets-report.txt"
MANIFEST="$ROOT/build-all-assets-manifest.txt"

set +e
python3 "$ROOT/scripts/build_all_assets.py" "$@"
status=$?
set -e

if [ -f "$REPORT" ]; then
  echo
  echo "=== build-all-assets-report.txt ==="
  cat "$REPORT"
fi

if [ "$status" -eq 0 ] && [ -f "$MANIFEST" ]; then
  echo
  echo "=== build-all-assets-manifest.txt ==="
  cat "$MANIFEST"
fi

exit "$status"
