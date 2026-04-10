# Order file demo

## Unified build entry point

Use [scripts/cory_build.py](Z:/home/pooppoop/ndk-samples/Cory/scripts/cory_build.py) as the single build orchestrator for this project.

Common flows:

```bash
python scripts/cory_build.py doctor
python scripts/cory_build.py full --sync-node-ec2
python scripts/cory_build.py full --rebuild-node-ec2
python scripts/build_all_assets.py --fetch-prebuilt --skip-node
```

What it does:

- `doctor`: validates local prerequisites plus EC2 AWS access
- `full --sync-node-ec2`: pulls the packaged `third_party/node24-android` bundle from EC2, then builds the app locally
- `full --rebuild-node-ec2`: rebuilds the authoritative Android Node bundle on EC2, promotes it into the packaged bundle there, syncs it back here, then builds the app locally
- `build_all_assets.py --fetch-prebuilt`: restores archived Android payloads from S3 before validation/build, which is the intended path for heavyweight artifacts that are excluded from the source bundle

Source of truth for the custom Android Node build:

- EC2 host: `cory@ec2-3-120-153-36.eu-central-1.compute.amazonaws.com`
- canonical Node source: `/home/cory/node-clean`
- canonical `node-cmake-build`: `/home/cory/node-cmake-build-clean`
- canonical rebuild output: `/home/cory/node24-android-rebuild`
- packaged bundle synced into this repo: `/home/cory/Cory/third_party/node24-android`

## Local full asset build

Use [scripts/build_all_assets.py](Z:/home/pooppoop/ndk-samples/Cory/scripts/build_all_assets.py) to build the vendored assets and the Android app from one entry point.

Requirements:

- Linux environment or container
- `ANDROID_SDK_ROOT` or `ANDROID_HOME` set
- Java 17
- Rust + `cargo-ndk`
- standard native build tools

Example:

```bash
python3 scripts/build_all_assets.py
```

Useful variants:

```bash
python3 scripts/build_all_assets.py --doctor-only
python3 scripts/build_all_assets.py --fetch-prebuilt
python3 scripts/build_all_assets.py --skip-node
python3 scripts/build_all_assets.py --skip-ripgrep
python3 scripts/build_all_assets.py --skip-app
```

Prebuilt-asset flow:

- source zips for CodeBuild intentionally exclude heavyweight payloads such as `third_party/node24-android/` and `third_party/ripgrep/target/`
- `scripts/fetch_prebuilt_assets.py` restores archived payload tarballs from S3
- `app/build.gradle` now auto-fetches the required payloads during a fresh build when they are missing
- `codebuild/android-full/post_build_publish.sh` now republishes stable tarballs under `s3://$CORY_PUBLIC_ARTIFACTS_BUCKET/${CORY_PREBUILT_PREFIX:-builds/prebuilt/latest/}`
- `codebuild/android-full/buildspec.yml` can opt into this path with `CORY_FETCH_PREBUILT=1`

Node embed note:

- the app still packages the standalone `node` binary into the sandbox when present
- native linking against `liblibnode.a` is now opt-in via `-PcoryEnableNodeEmbed=true`
- this keeps Compose/frontend iteration unblocked when the Node embed bundle is absent and only the shell-level `node` command path matters

The script uses the existing repo-owned dependency builders:

- `codebuild/node/build-node-android.sh`
- `cargo ndk` for `third_party/ripgrep`
- Gradle for BusyBox, Rust, Python asset packaging, and `:app:assembleDebug`

## Docker build image

There is also a container image definition at [docker/android-build/Dockerfile](Z:/home/pooppoop/ndk-samples/Cory/docker/android-build/Dockerfile).

Build it:

```bash
docker build -t cory-android-build -f docker/android-build/Dockerfile .
```

Run it against this repo:

```bash
docker run --rm -it -v "$PWD":/workspace/Cory cory-android-build
```

This copy is self-contained under `Cory/` and does not depend on sibling
modules or shared build files from the parent repository.

The Android app shell now follows the AGDK `agdktunnel` pattern more closely:
`Cory` launches through `androidx.games:games-activity`, uses
`android.app.lib_name=orderfiledemo`, and includes a minimal `android_main`
loop so the Java layer can stay thin while the vendored native stack remains
owned by `Cory/app/src/main/cpp`.

`Cory/third_party/libgit2` is vendored from `https://github.com/libgit2/libgit2`
at tag `v1.9.2`. The app CMake now exposes a local `cory_libgit2` target that
wraps the vendored `libgit2` build and links it into `orderfiledemo`.

`Cory/third_party/python-cmake-buildsystem` is vendored from
`https://github.com/python-cmake-buildsystem/python-cmake-buildsystem` at tag
`v2.7`. The app CMake now exposes a local `cory_libpython` target that wraps
the vendored Python buildsystem in the same `add_subdirectory(...)` style used
for `libgit2`.

The Python wrapper is configured for `Python 3.12.10` and leaves source
downloading enabled, so the first configure/build that touches it will fetch the
matching CPython source tree into the local CMake build area.

`Cory/third_party/LiteRT-LM` is a sparse vendor from
`https://github.com/google-ai-edge/LiteRT-LM` at commit
`fd8e9f412078fd37f2ed3ad420a212f11b86232e` from the `main` branch as of
March 26, 2026. The currently materialized worktree includes the repo root plus
the C++ API docs and key native CMake entrypoints such as:

- `docs/api/cpp/*.md`
- `docs/getting-started/cmake.md`
- `c/CMakeLists.txt`
- `runtime/CMakeLists.txt`
- `runtime/conversation/CMakeLists.txt`
- `runtime/conversation/conversation.h`
- `runtime/components/constrained_decoding/CMakeLists.txt`
- `runtime/components/tool_use/CMakeLists.txt`

`Cory` no longer builds the vendored LiteRT-LM native source tree as part of
the Android app. That upstream path turned into a large Bazel-derived CMake
super-build with repeated Android `ExternalProject` graph issues.

The app now uses the published Android Maven package instead:

- `com.google.ai.edge.litertlm:litertlm-android:0.9.0-alpha06`

The vendored LiteRT-LM source remains in `third_party/` for reference and for
future JNI/native work, but it is not wired into `app/src/main/cpp/CMakeLists.txt`.

`Cory/third_party/node24-android` is a locally vendored Android Node.js 24
embed bundle copied from a remote CMake Android build on March 27, 2026.
`Cory` now exposes a local `cory_libnode` target that wraps:

- `lib/arm64-v8a/liblibnode.a`
- `include/src`
- `include/deps/v8`
- `include/deps/uv`

The full remote build also produced `node`, `node_mksnapshot`, and `cctest`,
but `Cory` currently wires only the embeddable static library target.

`Cory/rust/cory_rust` is a minimal Rust Android `staticlib` crate.
`app/build.gradle` now builds it with `cargo ndk -t arm64-v8a --platform 24 build`,
and `app/src/main/cpp/CMakeLists.txt` imports
`rust/cory_rust/target/aarch64-linux-android/debug/libcory_rust.a` as
`cory_librust`.

Order files are text files containing symbols representing functions names.
Linkers (lld) uses order files to layout functions in a specific order. These
binaries with ordered symbols will reduce page faults and improve a program's
launch time due to the efficient loading of symbols during a program’s
cold-start.

## Files

- app/src/main/cpp/orderfile.cpp: The source code for the orderfile library that
  is used by the Kotlin app.
- app/src/main/cpp/CMakeLists.txt: The CMakeLists either sets the orderfile
  library as generating profiles or loading the orderfile.
- app/src/main/java/MainActivity.kt: The Kotlin app source code.

## Profile Steps

1. For simplicity, we have setup the `CMakeLists.txt` and you just need make
   sure `set(GENERATE_PROFILES ON)` is not commented. You need to pass any
   optimization flag except `-O0`. The mapping file is not generated and the
   profile instrumentation does not work without an optimization flag.
2. Run the app on Android Studio. You can either run it on a physical or virtual
   device. You will see "Hello World" on the screen.
3. To pull the data from the device, you'll need to move it from an app-writable
   directory to a shell readable directory for adb pull.
4. Use `llvm-profdata` to merge all the raw files and create an orderfile.

```
adb shell "run-as com.example.orderfiledemo sh -c 'cat /data/user/0/com.example.orderfiledemo/cache/demo.profraw' | cat > /data/local/tmp/demo.profraw"
adb pull /data/local/tmp/demo.profraw .
<NDK_PATH>/toolchains/llvm/prebuilt/<ARCH>/bin/llvm-profdata merge demo.profraw -o demo.profdata 
<NDK_PATH>/toolchains/llvm/prebuilt/<ARCH>/bin/llvm-profdata order demo.profdata -o demo.orderfile
```

## Load Steps

1. For load, you need to uncomment
   `set(USE_PROFILE "${CMAKE_SOURCE_DIR}/demo.orderfile")` and make sure
   `set(GENERATE_PROFILES ON)` is commented.

2. If you want to validate the shared library's layout is different, you need to
   find `liborderfiledemo.so` and run `nm`

```
mv demo.orderfile app/src/main/cpp
nm -n liborderfiledemo.so
```

## Difference between Java and Kotlin App

The main difference between a Java app and a Kotlin app is the syntax. You can
easily change this Kotlin example into a Java example.

- Load Library

```
# Kotlin
companion object {
    init {
        System.loadLibrary("orderfiledemo")
    }
}

# Java
static {
    System.loadLibrary("orderfiledemo");
}
```

- Recognize an external method

```
# Kotlin
external fun runWorkload(tempDir: String)

# Java
private native void runWorkload(String tempDir);
```

- Get the cache directory

```agsl
# Kotlin
runWorkload(applicationContext.cacheDir.toString())

# Java
runWorkload(getcacheDir().toString())
```
