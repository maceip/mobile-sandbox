# Cory Sandbox Prep

This document captures the current sandbox direction for `/home/pooppoop/ndk-samples/Cory`.

## Runtime layout

The app now prepares `/data/data/<package>/files/inceptionsandbox` with:

- `workspace/`
- `tmp/`
- `state/`
- `site-packages/`
- `bin/`
- `dev/`
- `home/`

`sitecustomize.py` bootstraps this layout before user Python code runs.

## Current app surface

Current startup UX in `Cory`:

- left workspace browser
- center editor for the selected file
- bottom 2-row terminal strip
- script run button
- shell command execution against the same workspace

This is now backed by the real sandbox files, not a hardcoded hello-world demo.

## Shell strategy

Short term:

- keep a Python-backed pseudo-shell in `cory_process.py`
- intercept `subprocess.run`, `subprocess.Popen`, `check_call`, `check_output`, and `os.system`
- expose sandboxed commands first, not unrestricted host process execution

Current built-ins:

- `busybox`
- `echo`
- `pwd`
- `env`
- `ls`
- `cat`
- `cp`
- `mv`
- `rm`
- `mkdir`
- `find`
- `head`
- `tail`
- `touch`
- `wc`
- `uname`
- `sh`
- `which`
- `python`
- `rg`
- `git`

Medium term:

- keep the Python shim as compatibility glue for agent-generated code
- prefer real bundled `busybox` and `rg` binaries when present

## BusyBox research

The most useful Android-specific reference found was:

- `osm0sis/android-busybox-ndk`

Why it matters:

- it is specifically about building BusyBox with the Android NDK
- it documents Android build configs and patches
- it enumerates a very broad set of applets already known to build

Recommended direction:

- treat that repo as a build reference, not as the shipped source of truth
- vendor upstream BusyBox source separately
- use the Android NDK build guidance to produce an `arm64-v8a` binary for `Cory`

Current state:

- `topjohnwu/ndk-busybox` style Android.mk flow now builds successfully for `arm64-v8a`
- the fork needed Android-specific cleanup:
- remove stale unconditional SELinux linkage and sources
- disable stale baked SELinux and IPC syslog config in `include/autoconf.h`
- regenerate `include/applet_tables.h` and `include/NUM_APPLETS.h`
- patch `swaponoff.c` so pre-26 Android uses the fork's own `hasmntopt` implementation
- the resulting `busybox` binary is packaged into the APK as `assets/python/bin/busybox`
- the Python subprocess shim now falls back to real BusyBox applets for commands it does not implement itself

## /dev strategy

We do not have real kernel-backed device nodes inside app-private storage.

So the sandbox should provide mockable equivalents under `inceptionsandbox/dev`:

- `null`
- `zero`
- `random`
- `urandom`
- `tty`
- `stdin`
- `stdout`
- `stderr`

Current state:

- those placeholder files are created on launch

Likely next step:

- make the shell/process layer translate common `/dev/...` references into these mocks
- special-case `null`, `zero`, `random`, and `urandom` semantics in the shell backend

## ripgrep research

`ripgrep` itself is Rust-based and upstream documents normal source builds via Cargo.

Recommended direction:

- build `ripgrep` for `aarch64-linux-android` with Rust + Android target support
- use `cargo-ndk` to emit Android-targeted binaries in the layout Android expects
- if that build is delayed, keep the current Python `rg` fallback for agent scripts

Current state:

- `cargo-ndk` is installed locally
- `ripgrep` `15.1.0` builds successfully for `aarch64-linux-android`
- the resulting `rg` binary is packaged into the APK as `assets/python/bin/rg`
- the Python subprocess shim now prefers the real `rg` binary when it exists
- the Python fallback remains in place as a compatibility path

## git strategy

The `git` command in this sandbox should not spawn desktop Git.

Planned model:

- `git` resolves to a Cory-owned wrapper
- that wrapper translates selected commands into native operations backed by vendored `libgit2`

Current state:

- the shim now routes `git` into a built-in Python module backed by vendored `libgit2`
- currently implemented commands are `git version`, `git clone`, `git init`, `git status`, `git add`, `git commit -m`, `git log`, `git checkout`, `git worktree list`, and `git worktree add`

Current limitation:

- remote HTTPS clone is still disabled by `/home/pooppoop/ndk-samples/Cory/app/src/main/cpp/CMakeLists.txt`, which forces `USE_HTTPS OFF`
- local clone and local repository worktree operations are the safe supported path right now

Next step:

- define a small command surface first: `git init`, `git status`, `git add`, `git commit`, `git log`
- expose that through JNI/native helpers instead of trying to embed a full Git executable

## Editor research

Best fit found so far:

- `Rosemoe/sora-editor`

Why it is the strongest technical fit:

- Android-native code editor component
- active enough to matter
- supports syntax highlight, completion, search/replace, TextMate, and TreeSitter
- slots naturally into a View-based app like `Cory`

Important tradeoff:

- LGPL-2.1

Best fallback if LGPL is a problem:

- `massivemadness/EditorKit`

Why it is the fallback:

- Apache-2.0
- straightforward View integration
- plugin model

Tradeoff:

- looks less active and less capable than `sora-editor`

Current app state:

- `Cory` now has a built-in minimal editor and console surface using standard Android widgets
- that is enough to edit and run sandbox scripts now
- editor-package integration is still a product choice, not a blocker to the current sandbox runtime

Production note:

- `sora-editor` is still the stronger editor technically, but it is LGPL-2.1
- `EditorKit` remains the cleaner-license fallback
- the current built-in editor should be treated as the shortest path to emulator-visible functionality, not the final editor choice

## GameActivity text input

Official Android guidance for `GameActivity` text input is:

- show the keyboard with `GameActivity_showSoftInput()`
- poll `android_app::textInputState`
- read text via `GameActivity_getTextInputState()`
- optionally seed state with `GameActivity_setTextInputState()`
- use `GameActivity_getTextInput()` when lower-level `GameTextInput` access is needed

Current state:

- `Cory` already launches through `GameActivity`
- the new workspace/editor/terminal screen still uses Android view text widgets for editing and command entry
- `GameTextInput` native-state synchronization is not wired yet

Production direction:

1. keep `GameActivity`
2. move editor input state behind a native text model
3. use the official `GameActivity_*TextInput*` APIs to drive IME show/hide/state flow
4. treat the current Java widget editor as a transitional UI, not the final native text path

## Recommendation

1. Keep the current Python shell shim as the compatibility layer.
2. Treat real BusyBox as available now, but continue to keep `git` and Python execution as Cory-owned special cases.
3. Use the packaged Android `rg` binary by default, with the Python fallback retained as insurance.
4. Keep extending the `libgit2` wrapper for local repo operations, but do not pretend remote clone is solved until `USE_HTTPS` is revisited.
5. Decide explicitly between `sora-editor` and `EditorKit` before replacing the current editor surface.
6. Rewire text input through the official `GameActivity` / `GameTextInput` path once the emulator UI loop is validated.
