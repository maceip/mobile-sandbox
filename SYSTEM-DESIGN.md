# Cory System Design

Last updated: 2026-03-29

This document describes the interface-level design for `Cory`: how Android,
Java, JNI, native code, embedded runtimes, and the sandboxed shell fit
together.

## Design goal

`Cory` is an Android app that provides:

- a writable sandboxed workspace
- a code editor
- a small terminal surface
- embedded runtimes
- a Git client
- a shell-like command layer

The app must feel like a constrained local development environment without
pretending Android is a full Linux desktop.

## Top-level layers

There are five layers:

1. Android app shell
2. Java/Kotlin UI controller layer
3. JNI/native bridge
4. Embedded runtime layer
5. Sandbox filesystem/process layer

## Layer 1: Android app shell

Current entrypoint:

- `MainActivity` in:
  - `/home/pooppoop/ndk-samples/Cory/app/src/main/java/com/example/orderfiledemo/MainActivity.java`

Current base class:

- `com.google.androidgamesdk.GameActivity`

Planned role:

- own Android lifecycle
- own window/insets/orientation integration
- host the editor/file-tree/terminal surface
- eventually coordinate AGDK text input

## Layer 2: Java UI controller layer

Current concrete class:

- `MainActivity`

Current responsibilities:

- extract packaged Python assets
- create sandbox directories
- install packaged tools into sandbox `bin`
- render workspace list
- render current file in editor
- trigger save/run/command actions
- read terminal state files back into the UI

Planned split

The current `MainActivity` is overloaded. It needs to be split into focused
classes.

### Planned Android-side classes

1. `CoryActivity`
- `GameActivity` subclass
- owns lifecycle, permissions, IME, and root view

2. `CoryWorkspaceController`
- owns file list refresh
- create/open/save/rename/delete
- mediates between UI widgets and sandbox paths

3. `CoryEditorController`
- owns editor widget integration
- current-file dirty state
- selection, scrolling, future search/replace

4. `CoryTerminalController`
- owns command entry
- terminal output rendering
- command history
- run-status rendering

5. `CoryRuntimeController`
- calls JNI methods for:
  - bootstrap
  - run script
  - run command
  - get native summary

6. `CoryGameTextInputAdapter`
- future adapter between editor state and AGDK text input
- owns keyboard show/hide
- owns text selection/composition sync

## Layer 3: JNI/native bridge

Current native entrypoints are in:

- `/home/pooppoop/ndk-samples/Cory/app/src/main/cpp/orderfile.cpp`

Current JNI surface:

- `runWorkload(pythonHome, sandboxRoot, tempDir)`
- `runScript(pythonHome, sandboxRoot, tempDir, scriptPath)`
- `runCommand(pythonHome, sandboxRoot, tempDir, command)`
- `nativeSummary()`

Current role of native:

- initialize embedded Python
- set sandbox environment variables
- call Python runtime entrypoints
- expose `cory_native` Python module
- surface git/libgit2 helpers
- hold linked native dependencies

### Planned JNI-facing classes on the native side

1. `CoryRuntime`
- bootstrap runtimes
- own env setup
- own summary/status string generation

2. `CoryPythonBridge`
- initialize/finalize CPython
- invoke `cory_runtime.run_script`
- invoke `cory_runtime.run_command`

3. `CoryGitBridge`
- wrap `libgit2`
- expose repo operations into Python and later directly to Java if needed

4. `CoryNodeBridge`
- launch `node` runtime path
- later possible `libnode` embed path if we stop using the external binary

5. `CoryPlatformBridge`
- Android-specific native helpers
- future file import/export, clipboard, logs, metrics

## Layer 4: Embedded runtime layer

There are multiple runtime roles in `Cory`.

### CPython

Current status:

- primary embedded language runtime
- already proven on-device

Current Python bootstrap files:

- `/home/pooppoop/ndk-samples/Cory/app/src/main/python/sitecustomize.py`
- `/home/pooppoop/ndk-samples/Cory/app/src/main/python/cory_sandbox.py`
- `/home/pooppoop/ndk-samples/Cory/app/src/main/python/cory_runtime.py`
- `/home/pooppoop/ndk-samples/Cory/app/src/main/python/cory_process.py`

### Node

Current status:

- packaged/runtime-linked path exists
- not yet proven end-to-end in the app sandbox

Planned role:

- second script runtime for agent-generated JavaScript
- shell-accessible via `node`

### Rust

Current status:

- linked native support only
- compile/run deferred

Planned role:

- not launch-critical

## Layer 5: Sandbox filesystem and process layer

Current sandbox root:

- `<app files>/inceptionsandbox`

Current directories:

- `workspace/`
- `tmp/`
- `state/`
- `site-packages/`
- `bin/`
- `dev/`
- `home/`

This is the application-local compatibility layer that makes agent-written code
feel like it has a small machine to work with.

## Shell environment design

The shell environment must be as real as Android permissions allow.

That means:

- real writable files in app-private storage
- real outbound networking
- real bundled executables where possible
- controlled pseudo-process execution where Android does not behave like Linux

It does not mean:

- unrestricted host process spawning
- arbitrary system binaries
- global filesystem access
- root-only device nodes

## Interfaces required for a realistic shell

The shell layer needs explicit interfaces for each capability.

### 1. Filesystem interface

Purpose:

- give scripts and commands a stable writable filesystem

Required surface:

- workspace root
- temp root
- site-packages root
- home root
- file metadata
- recursive directory traversal
- safe path normalization

Current implementation:

- Java creates the directories
- Python shell commands operate directly on those paths

### 2. Process interface

Purpose:

- let scripts call shell commands and helper binaries

Required surface:

- run command
- capture stdout
- capture stderr
- exit code
- cwd
- env
- future cancellation

Current implementation:

- `cory_process.run`
- `cory_process.CoryPopen`
- monkeypatch of `subprocess` and `os.system`

Planned improvement:

- move process execution behind an explicit runtime service instead of relying
  entirely on monkeypatching

### 3. Shell command registry

Purpose:

- define what commands exist and how each is resolved

Current command classes:

1. Python built-ins
- `cat`
- `cp`
- `echo`
- `env`
- `false`
- `find`
- `head`
- `ls`
- `mkdir`
- `mv`
- `pwd`
- `python`
- `python3`
- `rm`
- `sh`
- `tail`
- `touch`
- `true`
- `uname`
- `wc`
- `which`

2. Native-backed special commands
- `git`

3. Bundled external binaries
- `busybox`
- `rg`
- `node`

### 4. Git interface

Purpose:

- provide repository operations without depending on desktop Git

Current implementation path:

- `git` command in `cory_process.py`
- `cory_native` extension module
- `libgit2` implementation in native C++

Current supported operations:

- `git version`
- `git clone`
- `git init`
- `git status`
- `git add`
- `git commit -m`
- `git log`
- `git checkout`
- `git worktree list`
- `git worktree add`

Planned additions:

- branch create/list/delete
- diff
- restore/reset semantics
- remote inspection

### 5. Networking interface

Purpose:

- support HTTP requests, sockets, and agent workflows

Android permission already present:

- `android.permission.INTERNET`

Required shell/runtime behavior:

- Python sockets and HTTP clients must work
- Node sockets and fetch/http must work
- shell commands that use networking must be supported either by real bundled
  binaries or explicit wrappers

Planned wrappers:

- `curl`-like command
- download/install path for packages or assets

### 6. Device-node compatibility interface

Purpose:

- reconcile `/dev/*` assumptions with Android app sandbox reality

Current state:

- placeholder files created in `dev/`

Needed behavior:

- `/dev/null`
- `/dev/zero`
- `/dev/random`
- `/dev/urandom`
- `/dev/stdin`
- `/dev/stdout`
- `/dev/stderr`
- `/dev/tty`

Planned implementation:

- shell/process translation layer
- not raw kernel-backed device nodes

### 7. Environment-variable interface

Purpose:

- give scripts predictable process context

Current env keys:

- `HOME`
- `TMPDIR`
- `PATH`
- `CORY_SANDBOX_ROOT`
- `CORY_SANDBOX_WORKSPACE`
- `CORY_SANDBOX_SITE_PACKAGES`
- `CORY_SANDBOX_BIN`
- `CORY_SANDBOX_DEV`
- `CORY_SANDBOX_STATE`

Planned additions:

- `PWD`
- language-runtime specific env values
- feature flags for shell capability discovery

### 8. Package-install interface

Purpose:

- install pure-Python packages and later JS packages into the sandbox

Needed for realistic agent workflows:

- Python package staging
- local wheel/tar install
- future constrained pip-like UX
- JS module staging strategy

Current state:

- not implemented

## Native-to-Java connection plan

The current design pushes too much policy into `MainActivity`.
The target design is:

1. Java UI gathers intent
- open file
- save file
- run file
- run command

2. Java controller calls a narrow JNI API
- `bootstrap()`
- `runScript(path)`
- `runCommand(command)`
- `gitOperation(...)` later if needed directly
- `getRuntimeState()`

3. Native layer dispatches to runtime service
- Python
- Node
- Git
- shell process service

4. Native layer returns structured results
- exit code
- stdout
- stderr
- artifact list
- updated status

This is better than the current design because it removes file-status transport
through ad hoc state files for every operation.

## Planned public interfaces

These are the interfaces I plan to expose clearly between layers.

### Java -> native

- `bootstrapSandbox() -> RuntimeState`
- `runScript(path) -> CommandResult`
- `runCommand(command, cwd, envOverrides) -> CommandResult`
- `listWorkspace(path) -> FileEntry[]`
- `gitCommand(args) -> CommandResult`

### Native -> Python

- import `cory_runtime`
- import `cory_process`
- import `cory_sandbox`
- import `cory_native`

### Python -> native (`cory_native`)

- git functions
- future shell/platform helpers
- future editor/runtime notifications if needed

### Shell-visible commands

- file commands
- Python
- Node
- BusyBox
- ripgrep
- git

## Features not yet discussed enough but required later

These are integration areas we have not fully designed yet.

1. File import/export with Android document providers
- open files from outside the sandbox
- export workspace files cleanly

2. Background-run policy
- today runs die with app lifecycle
- later need explicit foreground-only contract or a service model

3. Terminal scrollback and persistence
- command history
- larger output retention
- per-run artifacts

4. Package installation UX
- Python wheel install
- JS module install strategy
- trust/safety boundary

5. Search/indexing
- project-wide search
- symbol search
- background file indexing

6. Editor component replacement
- choose final editor component
- integrate syntax/highlight/completion/search

7. AGDK text input migration
- replace widget-based text entry with `GameTextInput` integration

8. ABI strategy
- decide whether to support only `arm64-v8a`
- or also add `x86_64` for emulator-first workflows

9. Release/runtime packaging strategy
- decide which runtimes are startup-critical
- decide whether Node remains static-linked or becomes separate packaging

10. Policy guardrails for agent-written code
- resource limits
- timeouts
- path restrictions
- network policy

## Immediate design priorities

The next architecture steps are:

1. prove JS end-to-end
2. split `MainActivity` responsibilities
3. decide final editor component
4. move shell execution to structured results instead of only state files
5. wire the future AGDK text-input adapter
