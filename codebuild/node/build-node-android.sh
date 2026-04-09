#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-}"

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
WORK="$ROOT/work"
OUT="$ROOT/out"
LOGS="$OUT/logs"
TOOLS_BIN="$WORK/tools-bin"
HOME_DIR="${HOME:-/root}"
PYENV_VERSION_ROOT="$HOME_DIR/.pyenv/versions/3.14.2/bin"
NODE_REPO_URL="${NODE_REPO_URL:-https://github.com/nodejs/node.git}"
NODE_REF="${NODE_REF:-v24.14.1}"
NODE_CMAKE_BUILD_REPO_URL="${NODE_CMAKE_BUILD_REPO_URL:-https://github.com/maceip/node-cmake-build.git}"
NODE_CMAKE_BUILD_REF="${NODE_CMAKE_BUILD_REF:-main}"
NODE_EXPECTED_SHA="${NODE_EXPECTED_SHA:-}"
NODE_CMAKE_BUILD_EXPECTED_SHA="${NODE_CMAKE_BUILD_EXPECTED_SHA:-}"
ANDROID_CMDLINE_TOOLS_URL="${ANDROID_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip}"
ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:-27.2.12479018}"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-24}"
ANDROID_TARGET_ARCH="${ANDROID_TARGET_ARCH:-arm64}"
ANDROID_ABI="${ANDROID_ABI:-arm64-v8a}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$WORK/android-sdk}"
ANDROID_NDK="$ANDROID_SDK_ROOT/ndk/${ANDROID_NDK_VERSION}"
NODE_ROOT="$WORK/node"
NODE_CMAKE_ROOT="$WORK/ncg"
HOST_TOOLS="$WORK/node-host-tools"
HOST_BUILD="$WORK/node-host-build"
ANDROID_SRC="$WORK/node-android-src"
ANDROID_BUILD="$WORK/node-android-build"
PATCH_FILE="$ROOT/patches/v8-android-execinfo.patch"
UV_PYTHON_VERSION="${UV_PYTHON_VERSION:-3.14.2}"
UV_VENV="$WORK/uv-venv"
UV_PYTHON_BIN="$UV_VENV/bin/python"
UV_REAL_PYTHON_BIN=""
NODE_PYTHON_BIN=""
UV_PIP_CMD=()
UV_PYTHON_CMD=()

mkdir -p "$WORK" "$OUT" "$LOGS" "$TOOLS_BIN"

log() {
  printf '[%s] %s\n' "$(date -Is)" "$*"
}

mark_safe_git_dir() {
  local repo_dir="$1"
  git config --global --add safe.directory "$repo_dir" >/dev/null 2>&1 || true
}

dump_py_env() {
  log "python env: PATH=$PATH"
  log "python env: HOME=${HOME:-}"
  log "python env: VIRTUAL_ENV=${VIRTUAL_ENV:-}"
  log "python env: UV_PYTHON_VERSION=$UV_PYTHON_VERSION"
  log "python env: which uv=$(command -v uv || true)"
  log "python env: which python=$(command -v python || true)"
  log "python env: which python3=$(command -v python3 || true)"
  log "python env: UV_VENV=$UV_VENV"
  log "python env: UV_PYTHON_BIN=$UV_PYTHON_BIN"
}

run_uv() {
  dump_py_env
  log "exec: uv $*"
  uv "$@"
}

run_uv_python() {
  dump_py_env
  log "exec: ${UV_PYTHON_CMD[*]} $*"
  "${UV_PYTHON_CMD[@]}" "$@"
}

pick_first_tool() {
  local candidate
  for candidate in "$@"; do
    if command -v "$candidate" >/dev/null 2>&1; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

verify_checkout() {
  local repo_dir="$1"
  local expected_sha="$2"
  local label="$3"
  if [ -z "$expected_sha" ]; then
    log "$label resolved commit: $(git -C "$repo_dir" rev-parse HEAD)"
    return 0
  fi
  local actual_sha
  actual_sha="$(git -C "$repo_dir" rev-parse HEAD)"
  if [ "$actual_sha" != "$expected_sha" ]; then
    log "$label checkout mismatch"
    log "expected: $expected_sha"
    log "actual:   $actual_sha"
    return 1
  fi
}

ensure_python() {
  if ! command -v uv >/dev/null 2>&1; then
    log "uv missing from install phase"
    return 1
  fi
  export UV_PYTHON_PREFERENCE="${UV_PYTHON_PREFERENCE:-only-managed}"
  export PATH="$HOME_DIR/.local/bin:$TOOLS_BIN:$PATH"
  run_uv python install "$UV_PYTHON_VERSION" >/dev/null
  rm -rf "$UV_VENV"
  run_uv venv --python "$UV_PYTHON_VERSION" "$UV_VENV" >/dev/null
  UV_REAL_PYTHON_BIN="$(readlink -f "$UV_PYTHON_BIN")"
  NODE_PYTHON_BIN="$(command -v python3)"
  UV_PYTHON_CMD=("$UV_REAL_PYTHON_BIN")
  UV_PIP_CMD=(uv pip install --python "$UV_PYTHON_BIN")
  dump_py_env
  log "exec: ${UV_PIP_CMD[*]} cmake ninja"
  "${UV_PIP_CMD[@]}" cmake ninja >/dev/null

  cat >"$TOOLS_BIN/python3.14" <<EOF
#!/usr/bin/env bash
set -euo pipefail
exec "$UV_REAL_PYTHON_BIN" "\$@"
EOF
  cp "$TOOLS_BIN/python3.14" "$TOOLS_BIN/python3"
  chmod +x "$TOOLS_BIN/python3.14" "$TOOLS_BIN/python3"
  mkdir -p "$PYENV_VERSION_ROOT"
  cp "$TOOLS_BIN/python3.14" "$PYENV_VERSION_ROOT/python3.14"
  cp "$TOOLS_BIN/python3" "$PYENV_VERSION_ROOT/python3"
  chmod +x "$PYENV_VERSION_ROOT/python3.14" "$PYENV_VERSION_ROOT/python3"
  export VIRTUAL_ENV="$UV_VENV"
  export PATH="$UV_VENV/bin:$TOOLS_BIN:$PATH"
}

prepare_output_dirs() {
  rm -rf "$OUT"
  mkdir -p "$OUT" "$LOGS" "$TOOLS_BIN"
}

install_android_sdk() {
  ensure_python
  export PATH="$HOME_DIR/.local/bin:$UV_VENV/bin:$TOOLS_BIN:$PATH"
  if ! command -v ninja >/dev/null 2>&1 || ! cmake --version 2>/dev/null | grep -Eq 'version (3\.[1-9][0-9]|[4-9]\.)'; then
    log "cmake/ninja missing from install phase"
    dump_py_env
    command -v cmake || true
    command -v ninja || true
    cmake --version || true
    ninja --version || true
    return 1
  fi

  if [ ! -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
    log "installing android cmdline tools"
    mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
    curl --fail --location --retry 5 --retry-delay 5 \
      -o "$WORK/cmdline-tools.zip" "$ANDROID_CMDLINE_TOOLS_URL"
    rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest" "$WORK/cmdline-tools-unpack"
    mkdir -p "$WORK/cmdline-tools-unpack"
    unzip -q "$WORK/cmdline-tools.zip" -d "$WORK/cmdline-tools-unpack"
    mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    mv "$WORK/cmdline-tools-unpack/cmdline-tools/"* \
      "$ANDROID_SDK_ROOT/cmdline-tools/latest/"
  fi

  export PATH="$HOME_DIR/.local/bin:$UV_VENV/bin:$TOOLS_BIN:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
  export JAVA_HOME
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null || true
  sdkmanager --sdk_root="$ANDROID_SDK_ROOT" "ndk;${ANDROID_NDK_VERSION}"
}

clone_sources() {
  mark_safe_git_dir "$ROOT"
  mark_safe_git_dir "$WORK"
  mark_safe_git_dir "$NODE_ROOT"
  mark_safe_git_dir "$NODE_CMAKE_ROOT"
  if [ ! -d "$NODE_ROOT/.git" ]; then
    log "cloning node"
    git clone --branch "$NODE_REF" --depth 1 "$NODE_REPO_URL" "$NODE_ROOT"
  else
    log "refreshing node"
    git -C "$NODE_ROOT" fetch --depth 1 origin "$NODE_REF"
    git -C "$NODE_ROOT" checkout -f FETCH_HEAD
    git -C "$NODE_ROOT" clean -fdx
  fi
  verify_checkout "$NODE_ROOT" "$NODE_EXPECTED_SHA" "node"

  if [ ! -d "$NODE_CMAKE_ROOT/.git" ]; then
    log "cloning node-cmake-build"
    git clone --branch "$NODE_CMAKE_BUILD_REF" --depth 1 \
      "$NODE_CMAKE_BUILD_REPO_URL" "$NODE_CMAKE_ROOT"
  else
    log "refreshing node-cmake-build"
    git -C "$NODE_CMAKE_ROOT" fetch --depth 1 origin "$NODE_CMAKE_BUILD_REF"
    git -C "$NODE_CMAKE_ROOT" checkout -f FETCH_HEAD
    git -C "$NODE_CMAKE_ROOT" clean -fdx
  fi
  verify_checkout "$NODE_CMAKE_ROOT" "$NODE_CMAKE_BUILD_EXPECTED_SHA" "node-cmake-build"
}

prepare_worktrees() {
  rm -rf "$HOST_TOOLS" "$HOST_BUILD" "$ANDROID_SRC" "$ANDROID_BUILD"
  git -C "$NODE_ROOT" worktree prune || true
  git -C "$NODE_ROOT" worktree add --force "$HOST_TOOLS" HEAD
  git -C "$NODE_ROOT" worktree add --force "$ANDROID_SRC" HEAD
  mark_safe_git_dir "$HOST_TOOLS"
  mark_safe_git_dir "$ANDROID_SRC"
}

patch_android_src() {
  log "patching android source"
  git -C "$ANDROID_SRC" apply --check "$PATCH_FILE" || true
  git -C "$ANDROID_SRC" apply "$PATCH_FILE" || true
}

build_host_tools() {
  log "configuring host tools"
  (
    cd "$HOST_TOOLS"
    local_cmake_root="../$(basename "$NODE_CMAKE_ROOT")"
    export PYTHON="$NODE_PYTHON_BIN"
    export npm_config_python="$PYTHON"
    export NODE_GYP_FORCE_PYTHON="$PYTHON"
    export GYP_DEFINES="python=${PYTHON}"
    if CC_CANDIDATE="$(pick_first_tool gcc10-gcc gcc10-cc gcc-10 gcc10 gcc14-gcc gcc14-cc gcc-14 gcc14)"; then
      export CC="$CC_CANDIDATE"
      if CXX_CANDIDATE="$(pick_first_tool gcc10-g++ g++10 g++-10 gcc14-g++ g++14 g++-14)"; then
        export CXX="$CXX_CANDIDATE"
      else
        export CXX=g++
      fi
    elif command -v gcc >/dev/null 2>&1 && command -v g++ >/dev/null 2>&1; then
      export CC=gcc
      export CXX=g++
    else
      export CC=clang
      export CXX=clang++
    fi
    log "host toolchain CC=$CC CXX=$CXX"
    "$CC" --version >"$LOGS/node-host-cc-version.log" 2>&1 || true
    "$CXX" --version >"$LOGS/node-host-cxx-version.log" 2>&1 || true
    ./configure --shared --with-intl=full-icu --openssl-no-asm \
      >"$LOGS/node-host-configure.log" 2>&1
    PYTHONPATH=tools/gyp/pylib:tools/v8_gypfiles \
      "$NODE_PYTHON_BIN" tools/gyp_node.py -f "$local_cmake_root/analyse.py" \
      >"$LOGS/node-host-analyse.log" 2>&1
    git ls-files "**.cmake" "**/CMakeLists.txt" | xargs -r rm -f
    PYTHONPATH=tools/gyp/pylib "$NODE_PYTHON_BIN" "$local_cmake_root/generate.py" \
      >"$LOGS/node-host-generate.log" 2>&1
  )

  log "building native cmake host tools"
  cmake -S "$HOST_TOOLS" -B "$HOST_BUILD" -GNinja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_STANDARD=20 \
    >"$LOGS/node-host-cmake-configure.log" 2>&1

  ninja -C "$HOST_BUILD" node node_mksnapshot >"$LOGS/node-host-build.log" 2>&1
}

build_android() {
  log "configuring android source"
  (
    cd "$ANDROID_SRC"
    local_cmake_root="../$(basename "$NODE_CMAKE_ROOT")"
    "$NODE_PYTHON_BIN" android_configure.py patch || true
    export PYTHON="$NODE_PYTHON_BIN"
    export GYP_DEFINES="target_arch=${ANDROID_TARGET_ARCH} v8_target_arch=${ANDROID_TARGET_ARCH} android_target_arch=${ANDROID_TARGET_ARCH} host_os=linux OS=android android_ndk_path=${ANDROID_NDK} python=${PYTHON}"
    ./android-configure "$ANDROID_NDK" "$ANDROID_API_LEVEL" "$ANDROID_TARGET_ARCH" \
      >"$LOGS/node-android-configure.log" 2>&1
    PYTHONPATH=tools/gyp/pylib:tools/v8_gypfiles NCG_TARGET_PLATFORM=android \
      "$NODE_PYTHON_BIN" tools/gyp_node.py -f "$local_cmake_root/analyse.py" \
      >"$LOGS/node-android-analyse.log" 2>&1
    git ls-files "**.cmake" "**/CMakeLists.txt" | xargs -r rm -f
    PYTHONPATH=tools/gyp/pylib "$NODE_PYTHON_BIN" "$local_cmake_root/generate.py" \
      >"$LOGS/node-android-generate.log" 2>&1
  )

  log "building android cmake output"
  cmake -S "$ANDROID_SRC" -B "$ANDROID_BUILD" -GNinja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_STANDARD=20 \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="$ANDROID_API_LEVEL" \
    -DANDROID_NDK="$ANDROID_NDK" \
    -DNCG_HOST_TOOLS_DIR="$HOST_BUILD" \
    >"$LOGS/node-android-cmake-configure.log" 2>&1

  ninja -C "$ANDROID_BUILD" node libnode >"$LOGS/node-android-build.log" 2>&1
}

package_outputs() {
  log "packaging outputs"
  local stage_root="$OUT/third_party/node24-android"
  local stage_bin="$stage_root/bin/$ANDROID_ABI"
  local stage_lib="$stage_root/lib/$ANDROID_ABI"
  local stage_include="$stage_root/include"
  mkdir -p "$OUT/meta" "$stage_bin" "$stage_lib" "$stage_include"

  if [ -d "$ANDROID_BUILD" ] && [ -f "$ANDROID_BUILD/node" ]; then
    cp "$ANDROID_BUILD/node" "$stage_bin/node"
  fi

  if [ -d "$ANDROID_BUILD" ]; then
    find "$ANDROID_BUILD" -maxdepth 2 -type f \
      \( -name 'libnode.so' -o -name 'liblibnode.so' -o -name 'liblibnode.a' \) \
      -exec cp {} "$stage_lib/" \;
    tar -C "$WORK" -czf "$OUT/node-android-build.tgz" node-android-build
  fi

  if [ -d "$ANDROID_SRC/src" ]; then
    mkdir -p "$stage_include/src"
    cp -R "$ANDROID_SRC/src/." "$stage_include/src/"
  fi
  if [ -d "$ANDROID_SRC/deps/v8/include" ]; then
    mkdir -p "$stage_include/deps/v8"
    cp -R "$ANDROID_SRC/deps/v8/include/." "$stage_include/deps/v8/"
  fi
  if [ -d "$ANDROID_SRC/deps/uv/include" ]; then
    mkdir -p "$stage_include/deps/uv"
    cp -R "$ANDROID_SRC/deps/uv/include/." "$stage_include/deps/uv/"
  fi

  if [ -d "$HOST_BUILD" ]; then
    tar -C "$WORK" -czf "$OUT/node-host-tools.tgz" node-host-build
  fi

  {
    echo "node_ref=$NODE_REF"
    echo "node_expected_sha=$NODE_EXPECTED_SHA"
    echo "node_actual_sha=$(git -C "$NODE_ROOT" rev-parse HEAD)"
    echo "node_cmake_build_ref=$NODE_CMAKE_BUILD_REF"
    echo "node_cmake_build_expected_sha=$NODE_CMAKE_BUILD_EXPECTED_SHA"
    echo "node_cmake_build_actual_sha=$(git -C "$NODE_CMAKE_ROOT" rev-parse HEAD)"
    echo "android_ndk=$ANDROID_NDK_VERSION"
    echo "android_api=$ANDROID_API_LEVEL"
    echo "android_abi=$ANDROID_ABI"
    echo "built_at=$(date -Is)"
    echo
    echo "[third_party/node24-android/bin]"
    find "$stage_root/bin" -type f -printf '%P|%s\n' | sort
    echo
    echo "[third_party/node24-android/lib]"
    find "$stage_root/lib" -type f -printf '%P|%s\n' | sort
    echo
    echo "[third_party/node24-android/include]"
    find "$stage_root/include" -type f -printf '%P\n' | sort | sed -n '1,200p'
  } >"$OUT/meta/manifest.txt"
}

case "$MODE" in
  prepare)
    prepare_output_dirs
    install_android_sdk
    clone_sources
    prepare_worktrees
    patch_android_src
    ;;
  build)
    prepare_output_dirs
    install_android_sdk
    clone_sources
    prepare_worktrees
    patch_android_src
    build_host_tools
    build_android
    ;;
  package)
    mkdir -p "$OUT" "$LOGS" "$TOOLS_BIN"
    package_outputs
    ;;
  *)
    echo "usage: $0 {prepare|build|package}" >&2
    exit 2
    ;;
esac
