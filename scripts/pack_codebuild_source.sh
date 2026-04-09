#!/usr/bin/env bash
# Rsync a trimmed Cory tree and zip it (for huge EC2 working trees).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="$(mktemp -d /tmp/cory-cb-stage.XXXXXX)"
ZIP="$(mktemp /tmp/cory-source-XXXXXX.zip)"
cleanup() {
  if [[ -n "${CORY_PACK_WITH_SUDO:-}" ]]; then
    sudo rm -rf "$STAGE"
  else
    rm -rf "$STAGE"
  fi
}
trap cleanup EXIT

cd "$ROOT"
EXCL=(
  '--exclude=.git/'
  '--exclude=build/'
  '--exclude=app/build/'
  '--exclude=app/.cxx/'
  '--exclude=*/build/'
  '--exclude=.gradle/'
  '--exclude=gradle/caches/'
  '--exclude=third_party/node24-android/'
  '--exclude=third_party/Python-*/'
  '--exclude=third_party/ripgrep/target/'
  '--exclude=codebuild/node/out/'
  '--exclude=codebuild/node/work/'
  '--exclude=rust/target/'
  '--exclude=work/'
  '--exclude=*.tgz'
  '--exclude=*.zip'
  '--exclude=report_assets/'
  '--exclude=reconstruction/'
  '--exclude=.idea/'
  '--exclude=*.iml'
)

if [[ -n "${CORY_PACK_WITH_SUDO:-}" ]]; then
  sudo rsync -a --delete "${EXCL[@]}" ./ "$STAGE/"
  sudo rm -f "$ZIP"
  (cd "$STAGE" && sudo zip -rq "$ZIP" .)
  sudo chown "$(id -u):$(id -g)" "$ZIP"
else
  rsync -a "${EXCL[@]}" ./ "$STAGE/"
  rm -f "$ZIP"
  (cd "$STAGE" && zip -rq "$ZIP" .)
fi

echo "$ZIP"
