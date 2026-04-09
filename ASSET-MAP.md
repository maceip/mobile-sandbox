# Cory Asset Map

Timestamp: 2026-03-29 Europe/Berlin

This is the file-state handoff for the assets required to build and run `Cory`.

## Build-critical assets

### App source

- Local root:
  - `/home/pooppoop/ndk-samples/Cory`
- Remote root:
  - `/home/cory/ndk-samples/Cory`

### CPython Android package

Required roots:

- `third_party/python-android/prefix/include/python3.14`
- `third_party/python-android/prefix/lib`
- `third_party/python-android/prefix/lib/python3.14`

Status:

- Local `Python.h`:
  - `/home/pooppoop/ndk-samples/Cory/third_party/python-android/prefix/include/python3.14/Python.h|4399|2026-02-03 16:39:01 +0100`
- Remote `Python.h`:
  - `/home/cory/ndk-samples/Cory/third_party/python-android/prefix/include/python3.14/Python.h|4399|2026-02-03 15:39:01 +0000`
- Local stdlib tree:
  - files=`2481`, dirs=`203`
- Remote stdlib tree:
  - files=`2481`, dirs=`203`

Shared libs required:

- `libpython3.14.so`
- `libcrypto_python.so`
- `libssl_python.so`
- `libsqlite3_python.so`

Status:

- Present locally and remotely

### App Python glue

Required files:

- `app/src/main/python/sitecustomize.py`
- `app/src/main/python/cory_sandbox.py`
- `app/src/main/python/cory_runtime.py`
- `app/src/main/python/cory_process.py`
- `app/src/main/python/cory_bootstrap.py`

Status:

- Local tree:
  - `/home/pooppoop/ndk-samples/Cory/app/src/main/python|files=9|dirs=1|mtime=1774693483.7163959`
- Remote tree:
  - `/home/cory/ndk-samples/Cory/app/src/main/python|files=5|dirs=0|mtime=1774559449.0`

Notes:

- The local count is higher because of local cache/generated noise.
- The remote tree has the required five real source files.

### libgit2

Required root:

- `third_party/libgit2`

Required representative file:

- `third_party/libgit2/include/git2.h`

Status:

- Local:
  - `/home/pooppoop/ndk-samples/Cory/third_party/libgit2/include/git2.h|1858|2026-03-26 09:12:39 +0100`
- Remote:
  - `/home/cory/ndk-samples/Cory/third_party/libgit2/include/git2.h|1858|2026-03-26 08:12:39 +0000`
- Local tree:
  - files=`11894`, dirs=`3214`
- Remote tree:
  - files=`11894`, dirs=`3214`

### BusyBox

Required file:

- `third_party/ndk-busybox-ref/libs/arm64-v8a/busybox`

Status:

- Local:
  - `/home/pooppoop/ndk-samples/Cory/third_party/ndk-busybox-ref/libs/arm64-v8a/busybox|1950984|2026-03-29 00:21:55 +0100`
- Remote:
  - `/home/cory/ndk-samples/Cory/third_party/ndk-busybox-ref/libs/arm64-v8a/busybox|1950984|2026-03-29 00:15:04 +0000`

### ripgrep

Required file:

- `third_party/ripgrep/target/aarch64-linux-android/release/rg`

Status:

- Local:
  - `/home/pooppoop/ndk-samples/Cory/third_party/ripgrep/target/aarch64-linux-android/release/rg|29267160|2026-03-26 22:11:45 +0100`
- Remote:
  - `/home/cory/ndk-samples/Cory/third_party/ripgrep/target/aarch64-linux-android/release/rg|29267160|2026-03-26 21:11:45 +0000`

### Rust staticlib

Required file:

- `rust/cory_rust/target/aarch64-linux-android/debug/libcory_rust.a`

Status:

- Local:
  - `/home/pooppoop/ndk-samples/Cory/rust/cory_rust/target/aarch64-linux-android/debug/libcory_rust.a|21690526|2026-03-28 10:47:34 +0100`
- Remote:
  - `/home/cory/ndk-samples/Cory/rust/cory_rust/target/aarch64-linux-android/debug/libcory_rust.a|21850416|2026-03-27 16:22:14 +0000`

Notes:

- Both exist.
- The sizes differ.
- Treat the remote artifact as potentially stale or rebuilt from a different local state.

### Node runtime binary

Required file for current JS proof path:

- `third_party/node24-android/bin/arm64-v8a/node`

Status:

- Local:
  - missing
- Remote:
  - `/home/cory/ndk-samples/Cory/third_party/node24-android/bin/arm64-v8a/node|954668472|2026-03-29 00:14:33 +0000`

Problem:

- Local machine does not currently have the packaged Android `node` binary in the `Cory` tree.
- Remote machine does.

### Node static library

Required file for current native link path:

- `third_party/node24-android/lib/arm64-v8a/liblibnode.a`

Status:

- Local:
  - missing
- Remote:
  - `/home/cory/ndk-samples/Cory/third_party/node24-android/lib/arm64-v8a/liblibnode.a|231455388|2026-03-27 09:44:30 +0000`

Problem:

- Local machine does not currently have the packaged Node static lib in the `Cory` tree.
- Remote machine does.

### Node include trees

Required roots:

- `third_party/node24-android/include/src`
- `third_party/node24-android/include/deps/v8`
- `third_party/node24-android/include/deps/uv`

Status:

- Local `include/src`:
  - missing
- Local `include/deps/v8`:
  - missing
- Local `include/deps/uv`:
  - missing
- Remote `include/src`:
  - files=`429`, dirs=`9`
- Remote `include/deps/v8`:
  - files=`121`, dirs=`3`
- Remote `include/deps/uv`:
  - files=`14`, dirs=`1`

Problem:

- Local machine is currently missing the Node header trees that `CMakeLists.txt` expects.
- Remote machine has them.

## Build outputs

### APK

- Local:
  - `/home/pooppoop/ndk-samples/Cory/app/build/outputs/apk/debug/app-debug.apk|405967030|2026-03-29 00:22:21 +0100`
- Remote:
  - `/home/cory/ndk-samples/Cory/app/build/outputs/apk/debug/app-debug.apk|402005198|2026-03-29 00:15:38 +0000`

Notes:

- Both builds exist.
- Sizes differ.
- They were not produced from identical asset states.

## Remote-only source/build trees

These are not runtime assets for the app, but they are required for the fresh
Node rebuild effort on EC2:

- `/home/cory/node`
- `/home/cory/node-cmake-build`
- `/home/cory/node-native-tools`
- `/home/cory/node-android-cmake-src`
- `/home/cory/node-android-cmake-build`

## Missing or problematic items

1. Local `third_party/node24-android` is incomplete.
- missing local `node`
- missing local `liblibnode.a`
- missing local Node include trees

2. Remote Rust archive differs from local.
- do not assume they are equivalent

3. Local and remote APKs differ in size.
- do not assume they were built from the same exact asset set

4. Current EC2 emulator is `x86_64`, while current app packaging path is `arm64-v8a`.
- this matters for emulator validation

## Short conclusion

The current machine state is asymmetric:

- Python/libgit2/busybox/ripgrep exist on both machines
- Node assets currently exist only on the remote machine in usable form
- local `Cory` tree is not currently a complete source-of-truth for Node assets
