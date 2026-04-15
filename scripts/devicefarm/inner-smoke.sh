#!/system/bin/sh
# Runs as the app UID via run-as. Exercises the four smoke test commands.
# Invoked by the Device Farm test spec as:
#   adb push inner-smoke.sh /data/local/tmp/cory-smoke-inner.sh
#   adb shell run-as com.cory.app sh /data/local/tmp/cory-smoke-inner.sh "$NATIVE_LIB"
#
# Output goes to stdout, which Device Farm captures. No heredocs.

set -eu

NATIVE_LIB="${1:-}"
if [ -z "$NATIVE_LIB" ]; then
    echo "FAIL inner script needs nativeLibDir as arg 1"
    exit 19
fi

APP_FILES=/data/data/com.cory.app/files
cd "$APP_FILES"

export PATH="$APP_FILES/usr/bin:/system/bin:/system/xbin"
export LD_LIBRARY_PATH="$APP_FILES/usr/lib:$NATIVE_LIB"
export HOME="$APP_FILES/home"
export TMPDIR="$APP_FILES/tmp"
mkdir -p "$HOME" "$TMPDIR"

echo "----- env -----"
echo "PATH=$PATH"
echo "LD_LIBRARY_PATH=$LD_LIBRARY_PATH"
echo "HOME=$HOME"
echo "----- ls usr/bin -----"
ls -la "$APP_FILES/usr/bin" | head -20

echo ""
echo "----- 1. node -v -----"
NODE_OUT=$(node -v 2>&1)
echo "$NODE_OUT"
case "$NODE_OUT" in
    v[0-9]*) echo "node: OK" ;;
    *) echo "FAIL node -v = $NODE_OUT"; exit 20 ;;
esac

echo ""
echo "----- 2. python3 -c 'print(1+1)' -----"
PY_OUT=$(python3 -c "print(1+1)" 2>&1)
echo "$PY_OUT"
if [ "$PY_OUT" != "2" ]; then
    echo "FAIL python3 = $PY_OUT"
    exit 21
fi
echo "python: OK"

echo ""
echo "----- 3. git init + git worktree list (empty) -----"
rm -rf "$HOME/smoketest" "$HOME/smoketest-wt"
mkdir -p "$HOME/smoketest"
cd "$HOME/smoketest"
git init .
# lg2 dispatcher doesn't parse `git -c key=val` global flags, so set
# user.email / user.name via the `config` subcommand before committing.
git config user.email ci@cory.app
git config user.name CI
echo hi > a
git add a
git commit -m init
WT_LIST=$(git worktree list 2>&1)
echo "$WT_LIST"
if ! echo "$WT_LIST" | grep -q "no worktrees"; then
    echo "FAIL worktree list did not print '(no worktrees)'"
    exit 22
fi
echo "worktree list: OK"

echo ""
echo "----- 4. git worktree add -----"
ADD_OUT=$(git worktree add "$HOME/smoketest-wt" 2>&1)
echo "$ADD_OUT"
WT_LIST2=$(git worktree list 2>&1)
echo "$WT_LIST2"
if ! echo "$WT_LIST2" | grep -q "smoketest-wt"; then
    echo "FAIL second worktree list did not include smoketest-wt"
    exit 23
fi
echo "worktree add: OK"

echo ""
echo "ALL_TESTS_PASSED"
