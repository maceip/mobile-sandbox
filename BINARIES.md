# Cory — Native Binary Inventory

This is the complete list of every native binary the shell needs, where it
comes from, and how it ends up on `PATH`. If it's not in this document, it
doesn't exist in the build.

---

## Delivery Channels

The shell environment has two valid delivery mechanisms for native binaries:

### Channel A: Asset Binary

Cross-compiled binary → Gradle `preparePythonAssets` copies it to
`assets/python/bin/<name>` → Android packages into APK → at first launch
`CoryTerminalRuntime.syncBundledRuntime()` extracts to
`filesDir/python/bin/<name>` → `CoryTerminalRuntime.linkBundledTools()`
symlinks to `filesDir/usr/bin/<name>`.

**Used for:** `node`, `rg`, `python3`, `python3.14`, `busybox` (also)

**Pros:** Simple, works on most devices.
**Cons:** Some locked-down devices enforce W^X on `/data/data/<app>/files`
and the `+x` bit may not stick. Historical issues.

### Channel B: jniLibs PIE-as-`.so`

Cross-compiled **PIE executable** renamed to `lib<name>.so` → dropped in
`jniLibs/arm64-v8a/` → Android installer extracts it to `nativeLibDir`
(always executable, even on locked devices) → `TerminalBootstrap` symlinks
to `filesDir/usr/bin/<name>`.

**Used for:** `bash`, `busybox`

**Pros:** Always executable, even on W^X-enforcing devices.
**Cons:** The binary must be PIE, must be renamed `lib*.so`, and Android's
build tools will try to validate it as a shared library (usually works, but
the `.so` lie occasionally confuses tooling).

---

## Binary Inventory

### Shipping Today (verified in S3 and build)

| Binary | Channel | Source | S3 Archive | Gradle task | Notes |
|---|---|---|---|---|---|
| `bash` | B | prebuilt PIE | `shell-deps-arm64-v8a.tgz` | `prepareNativeLibs` | Core shell |
| `busybox` | B | prebuilt PIE | `shell-deps-arm64-v8a.tgz` | `prepareNativeLibs` | 55 applets symlinked by TerminalBootstrap |
| `libpython3.14.so` | shared lib | CPython prefix | `python-android-prefix.tgz` | `prepareNativeLibs` | Shared library used by CPython C API and (intended) by `libpython_shell.so` |
| Python stdlib | asset tree | CPython prefix | `python-android-prefix.tgz` | `preparePythonAssets` | Extracted to `filesDir/python/lib/python3.14/` at first launch |
| `python3` | A | `prefix/bin/python3` | `python-android-prefix.tgz` | `preparePythonAssets` | **Asset binary** — linked by `CoryTerminalRuntime.linkBundledTools()` |
| `python3.14` | A | `prefix/bin/python3.14` | `python-android-prefix.tgz` | `preparePythonAssets` | Versioned symlink target |
| `node` | A | `third_party/node24-android/bin/arm64-v8a/node` | `node24-android.tgz` | `preparePythonAssets` | Real Node 24 executable |
| `rg` | A | `third_party/ripgrep/target/.../rg` | `ripgrep-arm64-v8a.tgz` | `preparePythonAssets` | Ripgrep |
| `libcory.so` | shared lib | CMake build from `app/src/main/cpp/` | N/A | `externalNativeBuild` | Contains libgit2, Rust FFI, Python C API bindings (in-process, not CLI) |

### Shell Wrappers (generated at runtime)

| Binary | Writer | Depends on | Notes |
|---|---|---|---|
| `pip` | `CoryTerminalRuntime.linkBundledTools()` | `python3` asset | Calls `python3 -m ensurepip --upgrade` on first use, then `python3 -m pip` |
| `pip3` | `CoryTerminalRuntime.linkBundledTools()` | `pip` wrapper | Thin alias |
| `npm` | `CoryTerminalRuntime.linkBundledTools()` | `node` + `python/lib/node_modules/npm/bin/npm-cli.js` | Calls node with npm-cli.js |
| `npx` | `CoryTerminalRuntime.linkBundledTools()` | `node` + `python/lib/node_modules/npm/bin/npx-cli.js` | Calls node with npx-cli.js |
| `python` | `CoryTerminalRuntime.linkBundledTools()` | `python3` asset | Alias symlink |

### Not Shipping — Missing Producers

| Binary | Target Channel | Why Missing | Action Required |
|---|---|---|---|
| **`git`** | A (asset binary) | No cross-compile script, no `third_party/git-android/`, no S3 bundle | Cross-compile `git` for `aarch64-linux-android` (mirror how Node was built), drop binary at `third_party/git-android/bin/arm64-v8a/git`, add `coryGitBinary` variable to `app/build.gradle`, add entry in `fetch_prebuilt_assets.py`, upload `git-android.tgz` to S3, add `"git"` to `CoryTerminalRuntime.linkBundledTools()` tool list |
| **`rustc`** / **`cargo`** | A (asset binary) | Too large (~150MB each) to bundle | Defer — not needed for Phase 1 agent targets |
| **`libpython_shell.so`** | B (jniLibs PIE) | We removed the CMake target | **No longer needed** — we're using the asset-channel `python3` binary from the Python prefix bundle. The `NATIVE_TOOL_BINARIES` entry for it should be removed from `TerminalBootstrap`. |

---

## Runtime Link Order (what TerminalBootstrap + CoryTerminalRuntime do)

1. **`TerminalBootstrap.ensureEnvironment()`**
   1. Create `usr/bin`, `usr/lib`, `home`, `tmp` dirs
   2. Link `libbash.so` (from nativeLibDir) → `usr/bin/bash`
   3. Link `libbusybox.so` (from nativeLibDir) → `usr/bin/busybox`
   4. Create 55 busybox applet symlinks (awk, cat, grep, ls, etc.)
   5. Write `.bashrc` and `.profile`
2. **`CoryTerminalRuntime.syncBundledRuntime()`**
   1. Extract `assets/python/` tree to `filesDir/python/` (first launch only, checked by version stamp)
3. **`CoryTerminalRuntime.linkBundledTools()`**
   1. For each asset tool (`node`, `rg`, `busybox`, `python3`, `python3.14`): if it exists in `filesDir/python/bin/`, symlink to `usr/bin/`
   2. If `python3` exists, create `python` symlink
   3. Write `pip` and `pip3` shell wrappers
   4. If `node` exists and npm tarball was extracted, write `npm` and `npx` shell wrappers

---

## PATH Expected at End of Bootstrap

```
usr/bin/
├── bash              (libbash.so)
├── busybox           (libbusybox.so)
├── [ busybox applets: awk, cat, chmod, cp, echo, grep, ls, sed, ... ]
├── node              (asset binary, Node 24)
├── rg                (asset binary, ripgrep)
├── python            → python3
├── python3           (asset binary, CPython 3.14)
├── python3.14        (asset binary, same)
├── pip               (shell wrapper → python3 -m pip)
├── pip3              (shell wrapper → pip)
├── npm               (shell wrapper → node + npm-cli.js)
├── npx               (shell wrapper → node + npx-cli.js)
└── git               ❌ NOT YET — needs cross-compiled binary
```

---

## How to Add a New Binary (template)

Pick a channel. Channel A (asset) is simpler unless the binary is executed
from a locked-down device and W^X is a concern.

### Channel A template

1. Cross-compile the binary for `aarch64-linux-android` (use NDK, see
   `codebuild/` for existing build scripts).
2. Drop it at `third_party/<name>-android/bin/arm64-v8a/<name>`.
3. In `app/build.gradle`:
   ```groovy
   def cory<Name>Binary = file("$rootDir/third_party/<name>-android/bin/${coryPythonAbi}/<name>")
   // add to coryMissingVendoredAssets
   // add to verifyVendoredAssets missing list
   // add to preparePythonAssets Copy:
   if (cory<Name>Binary.exists()) {
       into("python/bin") { from(cory<Name>Binary) }
   }
   ```
4. In `scripts/fetch_prebuilt_assets.py`:
   ```python
   ASSET_ARCHIVES["<name>"] = "<name>-android.tgz"
   ASSET_TARGETS["<name>"] = Path("third_party/<name>-android")
   ```
5. In `CoryTerminalRuntime.linkBundledTools()`:
   ```kotlin
   val tools = listOf("node", "rg", "busybox", "python3", "python3.14", "<name>")
   ```
6. Upload `<name>-android.tgz` to
   `s3://cory-android-ci-manual-publicartifactsbucket-kmzlqxd3q1il/builds/prebuilt/latest/`

### Channel B template

1. Cross-compile as a **PIE executable** (`-fPIE -pie`).
2. Rename output to `lib<name>.so`.
3. Add to the `shell-deps-arm64-v8a.tgz` bundle at `shell-deps/arm64-v8a/lib<name>.so`.
4. `prepareNativeLibs` already copies all `*.so` from `shell-deps` to jniLibs, so no Gradle change needed.
5. In `TerminalBootstrap.ensureEnvironment()`, add:
   ```kotlin
   linkNativeBinary(nativeLibDir, binDir, "lib<name>.so", "<name>")
   ```
6. Re-upload `shell-deps-arm64-v8a.tgz` to S3.

---

## Known Issues

- **`libpython_shell.so` references are stale.** `TerminalBootstrap.NATIVE_TOOL_BINARIES`
  and linking logic still reference `libpython_shell.so` as the desired
  python3 source. It doesn't exist. Python3 actually comes from the asset
  channel via `CoryTerminalRuntime`. These references should be removed.

- **`libgit.so` / `librust.so` references are stale.** `TerminalBootstrap.NATIVE_TOOL_BINARIES`
  expects `libgit.so` and `librust.so` in `nativeLibDir`. No producer exists
  for either. Remove the entries until real binaries are built.

- **Duplicate pip/npm bootstrap logic.** Both `TerminalBootstrap` and
  `CoryTerminalRuntime.linkBundledTools()` write pip and npm shell wrappers.
  `CoryTerminalRuntime` wins because it runs second. Remove the duplicate
  from `TerminalBootstrap`.

- **Silent asset failures.** If `python-android-prefix.tgz` doesn't contain
  `prefix/bin/python3`, `preparePythonAssets` skips it silently with
  `if (coryPythonBinary.exists())`. Build succeeds, runtime fails when user
  types `python3`. Should fail fast at build time.
