# Cory System State

Last updated: 2026-03-29

This document captures the exact current state of the `Cory` project across the
local machine and the active EC2 hosts.

## Source of truth

- Project root: `/home/pooppoop/ndk-samples/Cory`
- Main Android package: `com.example.orderfiledemo`
- Main activity: `com.example.orderfiledemo.MainActivity`

## Local machine

### Build state

- `Cory` builds end-to-end locally with Gradle.
- Main local APK output:
  - `/home/pooppoop/ndk-samples/Cory/app/build/outputs/apk/debug/app-debug.apk`

### Local runtime state

- Embedded CPython Android runtime is wired and packaged.
- `libgit2` is wired and callable from Python through `cory_native`.
- Rust static library is linked into `orderfiledemo`.
- Node static library is linked into `orderfiledemo`.
- Current UI is a Java/View-based editor + workspace + terminal surface inside
  `GameActivity`.

### Local known-good runtime behavior

- Python was previously proven on a real Android device.
- Git was previously proven on a real Android device.
- Git worktree operations were previously proven on a real Android device.

### Local known-missing runtime behavior

- JavaScript via `node` is not yet proven end-to-end on-device.
- Rust compile/run is deferred.
- `GameTextInput` is not wired yet; current input path is still widget-based.

## EC2 x86 host

- SSH target: `cory@3.120.153.36`
- Hostname: `ip-172-31-27-231`
- Architecture: `x86_64`

### Android SDK / emulator

- Android SDK root: `/home/cory/Android/Sdk`
- Active emulator AVD: `Cory36`
- Active emulator process:
  - `/home/cory/Android/Sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64-headless -avd Cory36 -accel off -no-window -no-audio -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect -netfast`
- Current ADB state on host:
  - `emulator-5554 device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:1`

### Remote APK state

- Remote APK exists:
  - `/home/cory/ndk-samples/Cory/app/build/outputs/apk/debug/app-debug.apk`
- Current remote APK size:
  - `384M`

### Remote Node artifact state in Cory

- Runtime binary present:
  - `/home/cory/ndk-samples/Cory/third_party/node24-android/bin/arm64-v8a/node`
- Static library present:
  - `/home/cory/ndk-samples/Cory/third_party/node24-android/lib/arm64-v8a/liblibnode.a`
- These are the artifacts currently used by `Cory`.

### Fresh remote Node build state

There are two separate remote Node build efforts:

1. Native host-tools build
- Worktree/build area:
  - `/home/cory/node-native-tools`
- Active build:
  - `make -j32 node node_mksnapshot genccode icupkg`
- Build log:
  - `/home/cory/node-native-tools-build.log`
- Current status:
  - still actively compiling host x86_64 V8/Node objects

2. Android CMake-prep source tree
- Source prep area:
  - `/home/cory/node-android-cmake-src`
- Latest generation log:
  - `/home/cory/node-android-cmake-generate.log`
- Current status:
  - source-prep path exists, but no successful final Android `node` build has
    been completed from this fresh-from-source path yet

### Remote known-good behavior

- The x86 EC2 host can build `Cory`.
- The x86 EC2 host can boot an emulator in headless software mode.
- The x86 EC2 host currently has a live ADB-visible emulator.

### Remote known-missing behavior

- The EC2 emulator has not yet been used to prove `Cory` end-to-end.
- The current APK is `arm64-v8a` only; the emulator is `x86_64`.
- That means the current EC2 emulator is useful for emulator bring-up and ADB
  infrastructure, but not yet a clean execution target for the current APK.

## EC2 ARM host

- SSH target: `cory@34.255.196.126`
- Hostname: `ip-172-31-7-169`
- Architecture: `aarch64`

### Current role

- Build/support host only.
- Not the active emulator host.

### Known limitation

- This host is not the working Android emulator path for `Cory`.

## Application architecture state

### What is already implemented

- Java `GameActivity` shell
- Workspace browser
- Simple editor
- Bottom terminal pane
- Python script execution
- Shell command execution
- CPython embedded runtime
- Git/libgit2 wrapper exposed into Python
- BusyBox packaging path
- ripgrep packaging path
- Node packaging path
- Rust packaging path

### What is still temporary

- Editor is still a plain `EditText`
- Text input is not using `GameTextInput`
- Shell is still a hybrid:
  - Python built-ins
  - libgit2-backed `git`
  - external binaries when present

## Runtime proof matrix

### Proven

- Python create/edit/run
- Git init/add/commit/worktree

### Not yet proven

- `node hello.js` in the app sandbox on-device
- JS file I/O / module import / JSON / fetch on-device
- Final emulator-driven UI loop on EC2
- Final AGDK text-input path

## Main technical risks right now

1. ABI mismatch
- Current app build is `arm64-v8a`
- Current remote emulator is `x86_64`

2. JS proof gap
- Node artifacts are packaged in the app path
- JS runtime has not yet been proven end-to-end in the actual app sandbox

3. UI/input gap
- Current UI works as a stopgap
- It is not yet the final AGDK-native text-input architecture

4. Shell fidelity gap
- Current shell is good enough for Python/git experiments
- It is not yet a fully hardened Unix-like environment

## Immediate next facts, not guesses

- We do not need more Python or git discovery work.
- We do need a real JS runtime proof.
- We do need to decide whether to produce an `x86_64` app build for the EC2
  emulator or move JS proof back onto a real arm64 device path.

## Missing runbook details

This section exists because the earlier state snapshot was not enough to resume
work without wasting time.

### Exact active command sequence for the Node build

Current active remote build host:

- `cory@3.120.153.36`

Current launcher script:

- `/home/cory/start-node-cmake-android.sh`

That script currently performs this sequence:

1. Use Node source tree:
- `/home/cory/node`

2. Ensure native host-tools worktree exists:
- `/home/cory/node-native-tools`

3. Build native host tools if they are not already complete:
- `make -j32 node node_mksnapshot genccode icupkg`

4. Ensure Android CMake source-prep worktree exists:
- `/home/cory/node-android-cmake-src`

5. Patch Android-specific Node source:
- `python3 android_configure.py patch || true`

6. Configure Node for Android analysis:
- `./android-configure /home/cory/Android/Sdk/ndk/30.0.14904198 24 arm64`

7. Run GYP analysis for Android:
- `PYTHONPATH=tools/gyp/pylib:tools/v8_gypfiles NCG_TARGET_PLATFORM=android python3 tools/gyp_node.py -f /home/cory/node-cmake-build/analyse.py`

8. Remove prior generated CMake:
- `git ls-files "**.cmake" "**/CMakeLists.txt" | xargs -r rm -f`

9. Generate CMake from analysed GYP:
- `PYTHONPATH=tools/gyp/pylib python3 /home/cory/node-cmake-build/generate.py`

10. Configure Android CMake build dir:
- `/home/cory/node-android-cmake-build`

11. Run CMake with Android toolchain and native host tools:
- `cmake -GNinja ... -DNCG_HOST_TOOLS_DIR=/home/cory/node-native-tools/out/Release ../node-android-cmake-src`

12. Build:
- `ninja node libnode`

### Exact log file paths and what healthy progress looks like

Main orchestrator logs:

- `/home/cory/start-node-cmake-android.nohup`
- `/home/cory/node-cmake-android-run.log`

Native host-tools logs:

- `/home/cory/node-native-tools-configure.log`
- `/home/cory/node-native-tools-build.log`

Older direct Android build log:

- `/home/cory/node-android-fresh-build/build.log`

Android CMake generation logs:

- `/home/cory/node-android-cmake-configure.log`
- `/home/cory/node-android-cmake-analyse.log`
- `/home/cory/node-android-cmake-generate.log`

Healthy progress looks like:

- compiler lines continuing to advance in the log
- object files accumulating under:
  - `/home/cory/node-native-tools/out/Release/obj.target/...`
  - later `/home/cory/node-android-cmake-build/...`
- no repeated immediate restarts
- no static repeated error tail

Unhealthy progress looks like:

- same error tail repeated across checks
- no file mtime movement in build outputs
- process exists but log stops changing

### Exact artifact paths expected from the build

Native host-tools expected outputs:

- `/home/cory/node-native-tools/out/Release/node`
- `/home/cory/node-native-tools/out/Release/node_mksnapshot`
- `/home/cory/node-native-tools/out/Release/icupkg`
- `/home/cory/node-native-tools/out/Release/genccode`

Android CMake expected outputs:

- `/home/cory/node-android-cmake-build/node`
- `/home/cory/node-android-cmake-build/liblibnode.so` or equivalent libnode output
- supporting generated CMake build artifacts under:
  - `/home/cory/node-android-cmake-build/`

`Cory` integration target artifacts if we keep the current binary path:

- runtime binary:
  - `/home/cory/ndk-samples/Cory/third_party/node24-android/bin/arm64-v8a/node`
- static or shared library path used by `Cory`:
  - `/home/cory/ndk-samples/Cory/third_party/node24-android/lib/arm64-v8a/liblibnode.a`
  - or replacement shared-lib path if we switch packaging shape later

### Exact test loop after the build finishes

Once the Android `node` build succeeds:

1. Copy or install the resulting Android `node` artifact into:
- `/home/cory/ndk-samples/Cory/third_party/node24-android/bin/arm64-v8a/node`

2. Rebuild `Cory`:
- `cd /home/cory/ndk-samples/Cory && ./gradlew :app:assembleDebug --console=plain`

3. Verify APK contains `assets/python/bin/node`

4. Install onto a real target

5. In the app:
- create `hello.js`
- run `node hello.js`

6. Then expand the JS proof beyond `console.log`:
- file write/read
- JSON parse/stringify
- local module import
- current working directory behavior

7. Capture result in terminal and state files

### Exact source-of-truth host/device for each task

Node source build source of truth:

- host: `cory@3.120.153.36`
- source tree: `/home/cory/node`

Current `Cory` Android build source of truth:

- local machine project:
  - `/home/pooppoop/ndk-samples/Cory`
- remote build mirror:
  - `/home/cory/ndk-samples/Cory`

Current emulator source of truth:

- host: `cory@3.120.153.36`
- emulator AVD: `Cory36`
- adb target: `emulator-5554`

Current proven runtime source of truth:

- Python/git proof came from a real device path, not from the EC2 emulator

### Exact “if X fails, do Y next” branches

If native host-tools build fails:

- inspect `/home/cory/node-native-tools-build.log`
- fix native x86_64 build first
- do not proceed to Android CMake build until host tools are complete

If Android GYP analysis fails:

- inspect:
  - `/home/cory/node-android-cmake-analyse.log`
- fix analysis/generator import or GYP env issues

If CMake generation fails:

- inspect:
  - `/home/cory/node-android-cmake-generate.log`
- fix generator assumptions before re-running configure/build

If Android CMake configure fails:

- inspect the CMake configure output in:
  - `/home/cory/node-cmake-android-run.log`
- fix toolchain path, host-tools path, or generated target graph

If Android build succeeds but `Cory` still cannot run JS:

- stop touching the Node source build
- verify packaged `node` path in APK
- verify extraction into sandbox `bin`
- verify `cory_process._handle_node`
- verify runtime on-device through the actual app loop

If EC2 emulator cannot run the APK because of ABI mismatch:

- move JS proof to a real arm64 device path
- or add an `x86_64` Android app build path explicitly

### Current known-bad paths to avoid repeating

- blind long `scp` of giant Node binaries without verifying progress
- stopping at “I verified the host state” instead of starting the build
- mixing host-tool outputs and Android outputs in the same `out/Release`
- treating old recollection as source of truth instead of re-checking host paths
- assuming the EC2 `x86_64` emulator can validate an `arm64-v8a` APK without extra work
