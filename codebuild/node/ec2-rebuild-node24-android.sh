#!/usr/bin/env bash
set -euo pipefail

NODE_SRC="${NODE_SRC:-/home/cory/node-clean}"
NODE_CMAKE_ROOT="${NODE_CMAKE_ROOT:-/home/cory/node-cmake-build-clean}"
HOST_TOOLS_DIR="${HOST_TOOLS_DIR:-/home/cory/build-clean}"
ANDROID_BUILD_DIR="${ANDROID_BUILD_DIR:-/home/cory/node24-android-rebuild}"
WORK_SRC="${WORK_SRC:-/tmp/node24-android-src}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/home/cory/Android/Sdk}"
ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:-30.0.14904198}"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-24}"
ANDROID_TARGET_ARCH="${ANDROID_TARGET_ARCH:-arm64}"
ANDROID_ABI="${ANDROID_ABI:-arm64-v8a}"
RUN_ROOT="${RUN_ROOT:-/home/cory/node24-android-rebuild-runs}"
ANDROID_NDK="$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
RUN_DIR="${RUN_ROOT}/${RUN_ID}"
LOG_FILE="${LOG_FILE:-$RUN_DIR/build.log}"
MANIFEST_FILE="${MANIFEST_FILE:-$RUN_DIR/manifest.txt}"

log() {
  printf '[%s] %s\n' "$(date -Is)" "$*"
}

require_path() {
  local path="$1"
  if [ ! -e "$path" ]; then
    log "missing required path: $path"
    exit 1
  fi
}

require_path "$NODE_SRC"
require_path "$NODE_CMAKE_ROOT/analyse.py"
require_path "$NODE_CMAKE_ROOT/generate.py"
require_path "$HOST_TOOLS_DIR/node"
require_path "$HOST_TOOLS_DIR/node_mksnapshot"
require_path "$HOST_TOOLS_DIR/tools/icu/genccode"
require_path "$HOST_TOOLS_DIR/tools/icu/icupkg"
require_path "$ANDROID_NDK/build/cmake/android.toolchain.cmake"

mkdir -p "$RUN_DIR"
exec > >(tee -a "$LOG_FILE") 2>&1

log "node source: $NODE_SRC"
log "node-cmake-build: $NODE_CMAKE_ROOT"
log "host tools: $HOST_TOOLS_DIR"
log "android build dir: $ANDROID_BUILD_DIR"
log "working source tree: $WORK_SRC"
log "android ndk: $ANDROID_NDK"
log "run dir: $RUN_DIR"
log "log file: $LOG_FILE"
log "manifest file: $MANIFEST_FILE"
log "node commit: $(git -C "$NODE_SRC" rev-parse HEAD)"
log "node-cmake-build commit: $(git -C "$NODE_CMAKE_ROOT" rev-parse HEAD)"

log "creating fresh disposable source worktree"
rm -rf "$WORK_SRC"
git -C "$NODE_SRC" worktree add --force --detach "$WORK_SRC" HEAD >/dev/null
trap 'git -C "$NODE_SRC" worktree remove --force "$WORK_SRC" >/dev/null 2>&1 || rm -rf "$WORK_SRC"' EXIT

cd "$WORK_SRC"
python3 android_configure.py patch || true
cp "$NODE_CMAKE_ROOT/analyse.py" "$WORK_SRC/tools/gyp/pylib/gyp/generator/ncg_analyse.py"
python3 - <<'PY'
from pathlib import Path
path = Path("/tmp/node24-android-src/tools/gyp/pylib/gyp/generator/ncg_analyse.py")
text = path.read_text(encoding="utf-8")
needle = "generator_default_variables = {\n"
insert = "generator_default_variables = {\n    'python': sys.executable,\n"
if "'python': sys.executable" not in text:
    text = text.replace(needle, insert, 1)
    path.write_text(text, encoding="utf-8")
PY
find . -name CMakeLists.txt -o -name "*.cmake" | xargs -r rm -f
rm -f gyp_analysis.json

export GYP_DEFINES="target_arch=$ANDROID_TARGET_ARCH v8_target_arch=$ANDROID_TARGET_ARCH android_target_arch=$ANDROID_TARGET_ARCH host_os=linux OS=android android_ndk_path=$ANDROID_NDK"

log "running android-configure"
./android-configure "$ANDROID_NDK" "$ANDROID_API_LEVEL" "$ANDROID_TARGET_ARCH"

log "capturing gyp analysis"
PYTHONPATH=tools/gyp/pylib:tools/v8_gypfiles \
NCG_TARGET_PLATFORM=android \
python3 tools/gyp_node.py -f ncg_analyse

log "removing prior generated CMake files"
git ls-files "**.cmake" "**/CMakeLists.txt" | xargs -r rm -f
find . -name CMakeLists.txt -o -name "*.cmake" | xargs -r rm -f

log "generating CMake from gyp analysis"
PYTHONPATH=tools/gyp/pylib \
python3 "$NODE_CMAKE_ROOT/generate.py"

log "configuring android cmake build"
rm -rf "$ANDROID_BUILD_DIR"
cmake -S "$WORK_SRC" -B "$ANDROID_BUILD_DIR" -GNinja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_CXX_STANDARD=20 \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ANDROID_ABI" \
  -DANDROID_PLATFORM="$ANDROID_API_LEVEL" \
  -DANDROID_NDK="$ANDROID_NDK" \
  -DNCG_HOST_TOOLS_DIR="$HOST_TOOLS_DIR"

log "building android node + libnode"
ninja -C "$ANDROID_BUILD_DIR" node libnode

if [ -f "$ANDROID_BUILD_DIR/node" ] && [ -f "$ANDROID_BUILD_DIR/liblibnode.a" ]; then
  {
    echo "run_id=$RUN_ID"
    echo "built_at=$(date -Is)"
    echo "node_src=$NODE_SRC"
    echo "node_commit=$(git -C "$NODE_SRC" rev-parse HEAD)"
    echo "node_cmake_root=$NODE_CMAKE_ROOT"
    echo "node_cmake_commit=$(git -C "$NODE_CMAKE_ROOT" rev-parse HEAD)"
    echo "host_tools_dir=$HOST_TOOLS_DIR"
    echo "android_build_dir=$ANDROID_BUILD_DIR"
    echo "android_sdk_root=$ANDROID_SDK_ROOT"
    echo "android_ndk=$ANDROID_NDK"
    echo "android_api_level=$ANDROID_API_LEVEL"
    echo "android_target_arch=$ANDROID_TARGET_ARCH"
    echo "android_abi=$ANDROID_ABI"
    echo
    echo "[artifacts]"
    ls -lh "$ANDROID_BUILD_DIR/node" "$ANDROID_BUILD_DIR/liblibnode.a"
    echo
    echo "[sha256]"
    sha256sum "$ANDROID_BUILD_DIR/node" "$ANDROID_BUILD_DIR/liblibnode.a"
  } >"$MANIFEST_FILE"
fi

log "done"
log "android node: $ANDROID_BUILD_DIR/node"
log "android libnode: $ANDROID_BUILD_DIR/liblibnode.a"
log "run manifest: $MANIFEST_FILE"
