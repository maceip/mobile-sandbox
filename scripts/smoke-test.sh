#!/usr/bin/env bash
# scripts/smoke-test.sh
#
# Installs a Cory APK on an attached Android device, launches the
# terminal, and exercises `node -v`, `python3 -c print(1+1)`,
# `git init`, `git worktree list`, `git worktree add` inside the app's
# sandbox. Fails on the first command that doesn't produce the
# expected output.
#
# This is the canonical smoke test. The CI workflow runs an equivalent
# script against a cloud device (Firebase Test Lab / AWS Device Farm /
# local emulator). Use this one for fast local iteration against a
# device plugged into your machine.
#
# Usage:
#   scripts/smoke-test.sh [--serial <adb-serial>] [--apk <path>]
#
#   --serial   adb device serial (default: whichever `adb devices` shows)
#   --apk      path to the APK to install (default: pull latest release
#              from GitHub if `gh` is on PATH, else error)
#   --keep     don't uninstall the app after the test passes
#
# Exit codes:
#   0   all smoke tests passed
#   10  could not find/install APK
#   11  app never reached "runtime ready" state
#   20  node -v failed
#   21  python3 -c 'print(1+1)' failed
#   22  git init/commit/worktree list failed
#   23  git worktree add failed

set -euo pipefail

PACKAGE="com.cory.app"
ACTIVITY="${PACKAGE}/.ComposeSandboxActivity"
APP_FILES="/data/data/${PACKAGE}/files"
RUNTIME_CANARY="files/usr/bin/git"

SERIAL=""
APK=""
KEEP=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial)
            SERIAL="$2"
            shift 2
            ;;
        --apk)
            APK="$2"
            shift 2
            ;;
        --keep)
            KEEP=1
            shift
            ;;
        -h|--help)
            sed -n '2,30p' "$0"
            exit 0
            ;;
        *)
            echo "unknown arg: $1" >&2
            exit 2
            ;;
    esac
done

_adb() {
    if [[ -n "$SERIAL" ]]; then
        adb -s "$SERIAL" "$@"
    else
        adb "$@"
    fi
}

log()  { printf '\033[1;36m[smoke]\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m[ ok ]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2; exit "${2:-1}"; }

# ---- Pick APK ---------------------------------------------------------
if [[ -z "$APK" ]]; then
    if command -v gh >/dev/null 2>&1; then
        log "no --apk given, pulling latest release via gh"
        mkdir -p build/smoke
        gh release download --repo maceip/mobile-sandbox \
            --pattern 'app-debug.apk' --dir build/smoke --clobber \
            || fail "gh release download failed" 10
        APK="build/smoke/app-debug.apk"
    elif [[ -f "app/build/outputs/apk/debug/app-debug.apk" ]]; then
        log "no --apk given, using local Gradle build output"
        APK="app/build/outputs/apk/debug/app-debug.apk"
    else
        fail "no --apk given and no gh/local APK found. try: --apk path/to/app-debug.apk" 10
    fi
fi
[[ -f "$APK" ]] || fail "APK not found: $APK" 10
log "using APK: $APK ($(wc -c <"$APK") bytes)"

# ---- Device check -----------------------------------------------------
DEVICES=$(_adb devices | awk 'NR>1 && $2=="device" {print $1}' | head -5)
if [[ -z "$DEVICES" ]]; then
    fail "no adb devices found. plug one in or pass --serial" 10
fi
if [[ -z "$SERIAL" ]]; then
    SERIAL=$(echo "$DEVICES" | head -1)
    log "using first connected device: $SERIAL"
fi

ABI=$(_adb shell getprop ro.product.cpu.abi | tr -d '\r')
SDK=$(_adb shell getprop ro.build.version.sdk | tr -d '\r')
log "device abi=$ABI sdk=$SDK"

if [[ "$ABI" != "arm64-v8a" ]]; then
    fail "device abi is $ABI, but our APK only ships arm64-v8a binaries" 10
fi

# ---- Install ----------------------------------------------------------
log "installing $APK (this can take ~30s for a ~450 MB debug build)"
_adb install -r -t "$APK" >/dev/null || fail "adb install failed" 10
ok "installed $PACKAGE"

# ---- Launch activity --------------------------------------------------
log "launching $ACTIVITY (triggers CoryTerminalRuntime.ensureReady())"
_adb shell am start -W -n "$ACTIVITY" >/dev/null || fail "am start failed" 10

# ---- Wait for runtime extraction --------------------------------------
log "waiting for binary extraction (canary: $RUNTIME_CANARY)"
READY=0
for i in $(seq 1 180); do
    if _adb shell run-as "$PACKAGE" test -e "$RUNTIME_CANARY" 2>/dev/null; then
        ok "runtime ready after ${i}s"
        READY=1
        break
    fi
    sleep 1
done
if [[ "$READY" != "1" ]]; then
    log "runtime never became ready, dumping logcat"
    _adb logcat -d -s TerminalBootstrap:V CoryTerminalRuntime:V AndroidRuntime:E | tail -200
    fail "runtime never became ready after 180s" 11
fi

# ---- Look up nativeLibraryDir ----------------------------------------
NATIVE_LIB=$(_adb shell dumpsys package "$PACKAGE" \
    | tr -d '\r' \
    | grep -Eo 'nativeLibraryDir=[^ ]+' \
    | head -1 \
    | cut -d= -f2)
[[ -n "$NATIVE_LIB" ]] || fail "could not find nativeLibraryDir" 11
log "nativeLibraryDir=$NATIVE_LIB"

# ---- Run the actual smoke tests inside the app sandbox ---------------
log "piping smoke test script through 'adb shell run-as $PACKAGE sh'"
echo ""

set +e
OUT=$(_adb shell run-as "$PACKAGE" sh <<SCRIPT 2>&1
set -eu

APP_FILES="$APP_FILES"
cd "\$APP_FILES"

export PATH="\$APP_FILES/usr/bin:/system/bin:/system/xbin"
export LD_LIBRARY_PATH="\$APP_FILES/usr/lib:${NATIVE_LIB}"
export HOME="\$APP_FILES/home"
export TMPDIR="\$APP_FILES/tmp"
mkdir -p "\$HOME" "\$TMPDIR"

echo "=========================================================="
echo " 1. node -v"
echo "=========================================================="
NODE_OUT=\$(node -v)
echo "\$NODE_OUT"
case "\$NODE_OUT" in
  v[0-9]*) ;;
  *) echo "__FAIL__node__\$NODE_OUT"; exit 20 ;;
esac

echo ""
echo "=========================================================="
echo " 2. python3 ssl/sqlite3 + arithmetic"
echo "=========================================================="
PY_OUT=\$(python3 -c "import ssl, sqlite3, json, os, urllib.request; print(1+1)" 2>&1)
echo "\$PY_OUT"
if [ "\$PY_OUT" != "2" ]; then
  echo "__FAIL__python__\$PY_OUT"
  exit 21
fi

echo ""
echo "=========================================================="
echo " 3. git init + git worktree list (main worktree line)"
echo "=========================================================="
rm -rf "\$HOME/smoketest" "\$HOME/smoketest-wt" "\$HOME/clonetest"
mkdir -p "\$HOME/smoketest"
cd "\$HOME/smoketest"
git init .
echo hi > a
git add a
git -c user.email=ci@cory.app -c user.name=CI commit -m init
WT_LIST=\$(git worktree list)
echo "\$WT_LIST"
echo "\$WT_LIST" | grep -Fq "\$HOME/smoketest" || {
  echo "__FAIL__worktree_list__\$WT_LIST"
  exit 22
}

echo ""
echo "=========================================================="
echo " 4. git worktree add"
echo "=========================================================="
git worktree add "\$HOME/smoketest-wt"
WT_LIST2=\$(git worktree list)
echo "\$WT_LIST2"
echo "\$WT_LIST2" | grep -q "smoketest-wt" || {
  echo "__FAIL__worktree_add__\$WT_LIST2"
  exit 23
}

echo ""
echo "=========================================================="
echo " 5. git clone https:// (OpenSSL)"
echo "=========================================================="
CLONE_OUT=\$(git clone https://github.com/octocat/Hello-World "\$HOME/clonetest" 2>&1)
echo "\$CLONE_OUT"
if [ ! -d "\$HOME/clonetest/.git" ]; then
  echo "__FAIL__clone__\$CLONE_OUT"
  exit 24
fi

echo ""
echo "=========================================================="
echo " 6. bash --version"
echo "=========================================================="
BASH_OUT=\$(bash --version 2>&1 | head -1)
echo "\$BASH_OUT"
echo "\$BASH_OUT" | grep -qi 'GNU bash' || {
  echo "__FAIL__bash__\$BASH_OUT"
  exit 25
}

echo ""
echo "=========================================================="
echo " 7. busybox echo"
echo "=========================================================="
BB_OUT=\$(busybox echo cory-busybox-test 2>&1)
echo "\$BB_OUT"
[ "\$BB_OUT" = "cory-busybox-test" ] || {
  echo "__FAIL__busybox__\$BB_OUT"
  exit 26
}

echo ""
echo "=========================================================="
echo " 8. pip --version"
echo "=========================================================="
PIP_OUT=\$(pip --version 2>&1)
echo "\$PIP_OUT"
echo "\$PIP_OUT" | grep -qiE 'pip [0-9]+' || {
  echo "__FAIL__pip__\$PIP_OUT"
  exit 27
}

echo ""
echo "=========================================================="
echo " 9. npm --version"
echo "=========================================================="
NPM_OUT=\$(npm --version 2>&1)
echo "\$NPM_OUT"
echo "\$NPM_OUT" | grep -qE '^[0-9]+[.][0-9]+[.][0-9]+' || {
  echo "__FAIL__npm__\$NPM_OUT"
  exit 28
}

echo ""
echo "ALL_TESTS_PASSED"
SCRIPT
)
RC=$?
set -e

echo "$OUT"
echo ""

# ---- Parse results ----------------------------------------------------
if [[ "$RC" == "0" ]] && echo "$OUT" | grep -q "ALL_TESTS_PASSED"; then
    ok "all smoke tests passed"
    if [[ "$KEEP" != "1" ]]; then
        log "uninstalling $PACKAGE (pass --keep to skip)"
        _adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
    fi
    exit 0
fi

# Specific error path
if echo "$OUT" | grep -q "__FAIL__node__"; then
    fail "node -v produced unexpected output" 20
elif echo "$OUT" | grep -q "__FAIL__python__"; then
    fail "python3 produced unexpected output" 21
elif echo "$OUT" | grep -q "__FAIL__worktree_list__"; then
    fail "git worktree list produced unexpected output" 22
elif echo "$OUT" | grep -q "__FAIL__worktree_add__"; then
    fail "git worktree add failed" 23
elif echo "$OUT" | grep -q "__FAIL__clone__"; then
    fail "git clone (HTTPS) failed" 24
elif echo "$OUT" | grep -q "__FAIL__bash__"; then
    fail "bash --version failed" 25
elif echo "$OUT" | grep -q "__FAIL__busybox__"; then
    fail "busybox failed" 26
elif echo "$OUT" | grep -q "__FAIL__pip__"; then
    fail "pip --version failed" 27
elif echo "$OUT" | grep -q "__FAIL__npm__"; then
    fail "npm --version failed" 28
else
    log "dumping recent logcat for context"
    _adb logcat -d -s TerminalBootstrap:V CoryTerminalRuntime:V AndroidRuntime:E DEBUG:V System.err:W | tail -100
    fail "smoke test script exited $RC" "$RC"
fi
