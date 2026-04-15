# Cory Handoff

Last updated: 2026-03-29

This file is the single-source handoff. It pulls together the current machine
state, asset state, AWS state, EC2 state, build status, logs, and exact next
steps.

## 1. Source of truth

- Local project:
  - `/home/pooppoop/ndk-samples/Cory`
- Remote x86 host:
  - `cory@3.120.153.36`
- Remote project:
  - `/home/cory/ndk-samples/Cory`
- Remote Node source:
  - `/home/cory/node`
- Remote emulator:
  - `emulator-5554`
- Main Android package:
  - `com.example.orderfiledemo`
- Main activity:
  - `com.example.orderfiledemo.MainActivity`

## 2. What already works

- `Cory` builds locally.
- `Cory` builds on the remote x86 host.
- Embedded CPython works on a real Android device.
- Git works on a real Android device.
- Git worktrees work on a real Android device.
- Remote EC2 emulator is up and visible to ADB.

## 3. What does not yet work

- JavaScript is not yet proven end-to-end inside the app.
- `node hello.js` has not yet been proven in the actual app sandbox.
- The EC2 emulator is `x86_64`, while the current app packaging path is `arm64-v8a`.
- `GameTextInput` is not wired yet.

## 4. AWS / Device Farm state

- AWS Device Farm was previously used for the real-device proof of:
  - Python
  - git
  - worktrees
- AWS CLI path from this shell:
  - `/mnt/c/Program Files/Amazon/AWSCLIV2/aws.exe`
- If auth is expired:
  - `TERM=dumb '/mnt/c/Program Files/Amazon/AWSCLIV2/aws.exe' login --remote --no-cli-pager`

Known command shape:

```bash
'/mnt/c/Program Files/Amazon/AWSCLIV2/aws.exe' devicefarm list-projects
'/mnt/c/Program Files/Amazon/AWSCLIV2/aws.exe' devicefarm list-device-pools --arn <project-arn>
'/mnt/c/Program Files/Amazon/AWSCLIV2/aws.exe' devicefarm create-upload --project-arn <project-arn> --name app-debug.apk --type ANDROID_APP
'/mnt/c/Program Files/Amazon/AWSCLIV2/aws.exe' devicefarm schedule-run --project-arn <project-arn> --app-arn <upload-arn> --device-pool-arn <pool-arn> --name cory-debug
```

## 5. EC2 emulator state

- Host:
  - `cory@3.120.153.36`
- Hostname:
  - `ip-172-31-27-231`
- Architecture:
  - `x86_64`
- Emulator process:
  - `/home/cory/Android/Sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64-headless -avd Cory36 -accel off -no-window -no-audio -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect -netfast`
- ADB state:
  - `emulator-5554 device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:1`

Important limitation:

- current APK/runtime path is `arm64-v8a`
- current EC2 emulator is `x86_64`

## 6. Current asset map

### Present on both local and remote

- CPython Android prefix
  - headers
  - stdlib
  - `libpython3.14.so`
  - `libcrypto_python.so`
  - `libssl_python.so`
  - `libsqlite3_python.so`
- `libgit2` source tree
- BusyBox binary
- ripgrep binary
- Rust archive
- Android app source tree

### Present remotely in usable form, missing locally in `Cory`

- `third_party/node24-android/bin/arm64-v8a/node`
- `third_party/node24-android/lib/arm64-v8a/liblibnode.a`
- `third_party/node24-android/include/src`
- `third_party/node24-android/include/deps/v8`
- `third_party/node24-android/include/deps/uv`

This is the main file-state asymmetry right now.

## 7. Exact timestamped asset status

### Local

- APK:
  - `/home/pooppoop/ndk-samples/Cory/app/build/outputs/apk/debug/app-debug.apk|405967030|2026-03-29 00:22:21 +0100`
- BusyBox:
  - `/home/pooppoop/ndk-samples/Cory/third_party/ndk-busybox-ref/libs/arm64-v8a/busybox|1950984|2026-03-29 00:21:55 +0100`
- ripgrep:
  - `/home/pooppoop/ndk-samples/Cory/third_party/ripgrep/target/aarch64-linux-android/release/rg|29267160|2026-03-26 22:11:45 +0100`
- Rust:
  - `/home/pooppoop/ndk-samples/Cory/rust/cory_rust/target/aarch64-linux-android/release/libcory_rust.a|21690526|2026-03-28 10:47:34 +0100`
- Python include:
  - `/home/pooppoop/ndk-samples/Cory/third_party/python-android/prefix/include/python3.14/Python.h|4399|2026-02-03 16:39:01 +0100`

Missing locally:

- `/home/pooppoop/ndk-samples/Cory/third_party/node24-android/bin/arm64-v8a/node`
- `/home/pooppoop/ndk-samples/Cory/third_party/node24-android/lib/arm64-v8a/liblibnode.a`
- `/home/pooppoop/ndk-samples/Cory/third_party/node24-android/include/src`
- `/home/pooppoop/ndk-samples/Cory/third_party/node24-android/include/deps/v8`
- `/home/pooppoop/ndk-samples/Cory/third_party/node24-android/include/deps/uv`

### Remote

- APK:
  - `/home/cory/ndk-samples/Cory/app/build/outputs/apk/debug/app-debug.apk|402005198|2026-03-29 00:15:38 +0000`
- Node binary:
  - `/home/cory/ndk-samples/Cory/third_party/node24-android/bin/arm64-v8a/node|954668472|2026-03-29 00:14:33 +0000`
- Node static lib:
  - `/home/cory/ndk-samples/Cory/third_party/node24-android/lib/arm64-v8a/liblibnode.a|231455388|2026-03-27 09:44:30 +0000`
- BusyBox:
  - `/home/cory/ndk-samples/Cory/third_party/ndk-busybox-ref/libs/arm64-v8a/busybox|1950984|2026-03-29 00:15:04 +0000`
- ripgrep:
  - `/home/cory/ndk-samples/Cory/third_party/ripgrep/target/aarch64-linux-android/release/rg|29267160|2026-03-26 21:11:45 +0000`
- Rust:
  - `/home/cory/ndk-samples/Cory/rust/cory_rust/target/aarch64-linux-android/release/libcory_rust.a|21850416|2026-03-27 16:22:14 +0000`
- Python include:
  - `/home/cory/ndk-samples/Cory/third_party/python-android/prefix/include/python3.14/Python.h|4399|2026-02-03 15:39:01 +0000`

Remote include trees exist:

- `/home/cory/ndk-samples/Cory/third_party/node24-android/include/src|files=429|dirs=9`
- `/home/cory/ndk-samples/Cory/third_party/node24-android/include/deps/v8|files=121|dirs=3`
- `/home/cory/ndk-samples/Cory/third_party/node24-android/include/deps/uv|files=14|dirs=1`

## 8. Problematic mismatches

1. Local `node24-android` tree is incomplete.
2. Local and remote APKs differ in size.
3. Local and remote Rust archives differ in size.
4. Current emulator ABI and app ABI do not match.

## 9. Current remote Node build path

Primary launcher:

- `/home/cory/start-node-cmake-android.sh`

What it does:

1. build native host tools in:
   - `/home/cory/node-native-tools`
2. configure Android analysis in:
   - `/home/cory/node-android-cmake-src`
3. generate CMake using:
   - `/home/cory/node-cmake-build`
4. configure/build Android Node in:
   - `/home/cory/node-android-cmake-build`

Logs:

- `/home/cory/start-node-cmake-android.nohup`
- `/home/cory/node-cmake-android-run.log`
- `/home/cory/node-native-tools-build.log`
- `/home/cory/node-android-cmake-generate.log`

Expected host-tool outputs:

- `/home/cory/node-native-tools/out/Release/node`
- `/home/cory/node-native-tools/out/Release/node_mksnapshot`
- `/home/cory/node-native-tools/out/Release/icupkg`
- `/home/cory/node-native-tools/out/Release/genccode`

Expected Android outputs:

- `/home/cory/node-android-cmake-build/node`
- `/home/cory/node-android-cmake-build/liblibnode.so` or equivalent libnode output

## 10. Current app wiring for Node

Current plan in the app is:

1. package Android `node` into `assets/python/bin/node`
2. extract assets into app files
3. copy tools into `inceptionsandbox/bin`
4. set `PATH` to sandbox `bin`
5. let `cory_process.py` resolve `node`
6. run `node hello.js` with:
   - cwd=`inceptionsandbox/workspace`
   - stdout/stderr captured into the terminal state

This is the current proof path.
It does not require embedded `libnode` execution first.

## 11. Exact next steps

1. Finish the remote Node CMake Android build.
2. Verify expected Android Node artifacts exist.
3. Keep the packaging path pointing at the real Node artifact.
4. Rebuild remote `Cory`.
5. Use one execution target only:
   - AWS Device Farm real device, or
   - EC2 emulator after ABI path becomes valid
6. Run:
   - create `hello.js`
   - run `node hello.js`
   - verify output
7. Expand JS proof:
   - file I/O
   - JSON
   - local module import

## 12. Exact commands

Start Node build:

```bash
ssh cory@3.120.153.36 /home/cory/start-node-cmake-android.sh
```

Watch Node build:

```bash
ssh cory@3.120.153.36 'tail -f /home/cory/node-cmake-android-run.log'
```

Build remote app:

```bash
ssh cory@3.120.153.36 'cd /home/cory/ndk-samples/Cory && ./gradlew :app:assembleDebug --console=plain'
```

Check emulator:

```bash
ssh cory@3.120.153.36 '/home/cory/Android/Sdk/platform-tools/adb devices -l'
```

AWS reauth:

```bash
TERM=dumb '/mnt/c/Program Files/Amazon/AWSCLIV2/aws.exe' login --remote --no-cli-pager
```

## 13. Do not repeat

- do not assume remote state from memory
- do not use blind large-file copy as the primary Node path
- do not split focus away from JS until `node hello.js` works
- do not stop at verification when the next command can be run
