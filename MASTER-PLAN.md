# Mobile Sandbox — Master Plan

This document is the single source of truth for architecture, current state,
and roadmap. It supersedes the scattered notes in `SYSTEM-DESIGN.md`,
`SANDBOX-PLAN.md`, and the various recovery docs.

---

## 1. What This App Is

Cory is a native Android terminal application that runs a real `bash` shell
over a PTY, with embedded Python (via `libpython3.14.so`) and a roadmap to
host AI coding agents (Claude Code, Codex, Gemini) running entirely on-device.

The primary user interaction is a terminal. The secondary path (for agents)
is a `runOnce()`-style command execution API. The app must:

- Feel better than Termux and better than Google's new Android Terminal
- Not get killed when backgrounded
- Support complex Python / Node / Rust projects (not just one-liners)
- Handle TUI apps (`htop`, `vim`, `less`) gracefully on narrow phone screens
- Eventually provide agent sandboxing via seccomp + egress control

---

## 2. Current Architecture

### Layer Diagram

```
┌─────────────────────────────────────────────────────┐
│  ComposeSandboxActivity                             │
│  (entry point, starts TerminalService, bootstraps)  │
├─────────────────────────────────────────────────────┤
│  TerminalScreen                                     │
│  └── CoryScaffold                                   │
│      ├── SessionTabs (top)                          │
│      ├── Content: terminal-first on all sizes       │
│      └── InputBar (bottom)                          │
├─────────────────────────────────────────────────────┤
│  AnsiTerminalEmulator (VT100/xterm screen model)    │
│  ├── Primary screen buffer (W x H char grid)        │
│  ├── Alt screen buffer (vim, less, htop)            │
│  ├── History buffer (scrollback)                    │
│  └── Full SGR, CSI, OSC, cursor, scroll regions     │
├─────────────────────────────────────────────────────┤
│  OutputProcessor (line parser + state machine)      │
│  ├── Init → LoggedIn → AwaitingPrompt → Ready       │
│  ├── Fullscreen detection (ESC[?1049h/l)            │
│  ├── Command tracking, progress lines (CR)          │
│  └── Emits CommandExecutionEvent, SessionOutputEvent│
├─────────────────────────────────────────────────────┤
│  TerminalManager (singleton coordinator)            │
│  ├── SessionManager (multi-session state)           │
│  ├── LocalTerminalProvider → Pty.start() → bash     │
│  ├── Output reader coroutine → OutputProcessor      │
│  └── SharedFlows for all events                     │
├─────────────────────────────────────────────────────┤
│  PTY (JNI → pty.c)                                  │
│  └── forkpty() → bash process with full env         │
└─────────────────────────────────────────────────────┘
```

### Two Parallel Processing Paths

Every byte from the PTY goes through two paths simultaneously:

1. **Display emulator** (`session.ansiParser`) — standard VT100/xterm screen
   at phone width, rendered by `CanvasTerminalView` (SurfaceView).

2. **Shadow emulator** (`session.shadowEmulator`) — fixed at 80×24, used by
   `TuiScraper` to decompose TUI app output into semantic regions
   (status bars, content, menus) that `TuiBridgeView` renders as native
   Compose widgets.

Both are fed in `OutputProcessor.processOutput()`.

### Native Dependency Pipeline

All native binaries used by the shell come from one place (S3), fetched by
the build, packaged into the APK, extracted to `nativeLibDir` at install,
and symlinked into `usr/bin/` by `TerminalBootstrap`.

```
S3: s3://cory-android-ci-manual-publicartifactsbucket-kmzlqxd3q1il/
    builds/prebuilt/latest/
    ├── node24-android.tgz
    ├── python-android-prefix.tgz       → libpython3.14.so
    ├── busybox-arm64-v8a.tgz
    ├── ripgrep-arm64-v8a.tgz
    ├── rust-cory_rust-arm64-release.tgz
    └── shell-deps-arm64-v8a.tgz        → libbash.so, libbusybox.so
        │
        ▼ scripts/fetch_prebuilt_assets.py
    third_party/
    ├── node24-android/
    ├── python-android/prefix/lib/libpython3.14.so
    ├── ripgrep/target/.../rg
    └── shell-deps/arm64-v8a/
        ├── libbash.so
        └── libbusybox.so
        │
        ▼ Gradle: prepareNativeLibs
    build/generated/cory/python-jniLibs/arm64-v8a/
    ├── libpython3.14.so
    ├── libpython_shell.so    (future: prebuilt from S3)
    ├── libbash.so
    └── libbusybox.so
        │
        ▼ Android packaging → APK → install
    /data/app/.../lib/arm64-v8a/  (nativeLibDir)
        │
        ▼ TerminalBootstrap.ensureEnvironment()
    filesDir/usr/bin/
    ├── bash      → libbash.so
    ├── busybox   → libbusybox.so (+ 55 applet symlinks)
    ├── python3   → libpython_shell.so    (+ python symlink)
    ├── node      → libnode.so             (when packaged)
    ├── git       → libgit.so              (when packaged)
    └── rustc     → librust.so             (when packaged)
```

---

## 3. Shipped vs Not Shipped

### Shipped

| Feature | Status | Where |
|---|---|---|
| Persistent PTY sessions | ✅ | `TerminalService` (foreground) + `TerminalManager` singleton |
| Foreground service + notifications | ✅ | `TerminalService.kt`, permissions in manifest |
| Terminal-first UX | ✅ | `CoryScaffold` shows terminal on all form factors |
| Upstream Operit purge | ✅ | ~1,629 lines removed — SSH, UpdateChecker, FTP, chroot, mirror sources, Chinese strings |
| Package rename → `com.cory.app` | ✅ | All source, manifest, native lib |
| Native lib rename → `libcory.so` | ✅ | CMake, JNI class refs |
| GameActivity removal | ✅ | Deleted `android_main.cpp`, `MainActivity.java`, deps, linker flags |
| TUI bridge scaffolding | ✅ | `tui/ScreenRegion.kt`, `TuiScraper.kt`, `TuiBridgeView.kt` + shadow emulator wiring |
| CI: build + semver release | ✅ | `.github/workflows/build-apk.yml` |
| S3 shell-deps bundle | ✅ | `shell-deps-arm64-v8a.tgz` uploaded, fetch script updated |
| Native tool binary map | ✅ | `TerminalBootstrap.NATIVE_TOOL_BINARIES` for libnode/libgit/librust |

### Not Shipped / Unverified

| Gap | Why it matters |
|---|---|
| End-to-end APK build never run | First `./gradlew :app:assembleDebug` will surface real issues |
| `libpython_shell.so` not in S3 bundle yet | Python interpreter wrapper exists as source but isn't prebuilt |
| `libnode.so`, `libgit.so`, `librust.so` PIE binaries don't exist | The Kotlin side has entries but the executables haven't been built |
| PTY TIOCSWINSZ pin when fullscreen | Shadow emulator is 80×24 but bytes arriving are formatted for phone width — TuiScraper sees phone-width data |
| Interactive input routing in TuiBridgeView | Read-only projection; can't click htop row to kill process, can't press `q` in less |
| Namespace package still `com.ai.assistance.operit.terminal` in `terminal/` module | Module namespace independent of app's `com.cory.app`; deep refactor |

---

## 4. Retrofit Roadmap (13 Workstreams)

From the audit of OpenSandbox, bVisor, and sandbox-agent, organized by phase.

### Phase 0 — Foundations

These unblock everything else. Build first.

**WS 4: Structured Event Protocol** (M) — no deps

Current events (`CommandExecutionEvent`, `SessionOutputEvent`) use
`MutableSharedFlow` with fixed buffers and no sequence numbers. Build a
sequenced, ring-buffered event bus.

```
terminal/src/main/java/.../terminal/events/
├── SequencedEvent.kt    # { sequenceNumber, sessionId, timestampMs }
├── EventBus.kt          # emit, subscribe, replay(fromSeq), latest(count)
└── RingBuffer.kt
```

All existing event emissions migrate to `EventBus.emit()`. AIDL parcelables
gain `sequenceNumber` field.

**WS 11: Process Log Ring Buffer** (M) — no deps

`TerminalSessionData.rawBuffer` and `currentCommandOutput` are `StringBuilder`
with 256KB cap and no monotonic indexing. Replace with `ProcessLogRingBuffer`:

```kotlin
class ProcessLogRingBuffer(maxBytes: Int = 256 * 1024) {
    fun append(text: String)
    fun toString(): String
    val sequenceNumber: Long  // monotonic, total bytes ever written
    fun snapshot(): ByteArray
    fun substring(start: Int, end: Int): String
    fun delete(start: Int, end: Int)  // StringBuilder compat shim
}
```

**WS 12: Separate PTY FDs** (M) — no deps

Current `pty.c` returns `int[2] = { pid, masterFd }`. For seccomp and
resize-signal-pipe work, extend to separate read/write/resize FDs:

```
int[3] = { pid, masterFd, resizePipeWriteEnd }
```

Reader loop uses `poll()` on master fd + signal pipe so resize requests can
interrupt reads. Simpler alternative for first pass: `@Volatile` resize flag
checked after each `read()`.

### Phase 1 — Core Platform

**WS 2: Pluggable Bootstrap** (S) — no deps

`TerminalBootstrap` is monolithic with hardcoded `BUSYBOX_APPLETS` and
`NATIVE_TOOL_BINARIES`. Convert to a registry of modules:

```
terminal/src/main/java/.../terminal/bootstrap/
├── BootstrapModule.kt        # interface { name, priority, install, isInstalled }
├── BootstrapEnvironment.kt   # filesDir, binDir, libDir, helpers
├── BootstrapRegistry.kt
├── CoreShellModule.kt        # bash + busybox
├── PythonModule.kt           # python3 + ensurepip
├── NodeModule.kt             # node + npm
└── ToolsModule.kt            # rg, git, rustc
```

**WS 9: Restart Policies** (S) — deps: WS 4

On PTY EOF, decide whether to restart based on a policy:

```kotlin
enum class RestartPolicy { NEVER, ALWAYS, ON_FAILURE }
data class RestartState(
    val policy: RestartPolicy,
    val maxRestarts: Int = 5,
    val restartCount: Int = 0,
    val backoffMs: Long = 1000
)
```

### Phase 2 — Capabilities

**WS 1/10: REPL Sessions** (M) — deps: WS 2

Add `SessionType` discriminator. `LocalTerminalProvider.startReplSession()`
execs `python3 -i` or `node --interactive`. `OutputProcessor.isPrompt()`
becomes a `PromptPattern` interface with per-type implementations so it
correctly handles `>>>` (Python) and `>` (Node) without false positives on
shell output.

**WS 3: COW Filesystem** (L) — deps: WS 2

`OverlayManager` with `createOverlay()`, `snapshot()`, `rollback()`,
`dispose()`. On devices with overlayfs support, uses `mount -t overlay`.
Fallback for non-root: hardlink tree (`cp -al`) for snapshots.

**WS 5: RunOnce API** (S) — deps: WS 4

Thin extension of `executeHiddenCommand` with structured return:

```kotlin
@Parcelize data class RunOutput(
    val stdout: String, val stderr: String,
    val exitCode: Int, val timedOut: Boolean, val truncated: Boolean,
    val durationMs: Long, val sequenceNumber: Long
) : Parcelable

suspend fun runOnce(
    command: String,
    timeoutMs: Long = 30_000,
    maxOutputBytes: Int = 1_048_576,
    env: Map<String, String>? = null,
    workingDir: File? = null
): RunOutput
```

### Phase 3 — Security & Network

**WS 6: Egress Control** (L) — deps: WS 2, WS 13

Android `VpnService` creates a TUN interface. Userspace DNS proxy
intercepts port 53 and applies domain-level allowlist/denylist. PTY child
processes inherit the VPN route. Documented limitation: bypassed if a
process uses raw IPs (not DNS).

**WS 7: Seccomp Filter** (M) — deps: WS 12

Install BPF filter in child after `forkpty()`, before `execve()`. Blocks:
`mount`, `umount2`, `ptrace`, `setns`, `unshare`, `bind`, `listen`,
`pivot_root`, `reboot`, `init_module`, `delete_module`, `kexec_load`.
Uses `SECCOMP_RET_ERRNO(EPERM)` not `KILL` for debuggability.

### Phase 4 — Integration

**WS 8: HTTP/JSON API** (M) — deps: WS 4, WS 5

NanoHTTPD bound to `127.0.0.1:8765` with per-launch bearer token auth:

```
POST   /sessions                    → create session / REPL
DELETE /sessions/{id}               → close
POST   /sessions/{id}/command       → sendCommand
POST   /sessions/{id}/input         → sendInput / sendRawInput
POST   /sessions/{id}/interrupt     → SIGINT
POST   /runOnce                     → runOnce
GET    /sessions                    → list
GET    /events?from=seqNo           → SSE stream
GET    /events/replay?from=seqNo    → batch replay
```

**WS 13: Agent Capability Matrix** (S) — deps: WS 2, WS 6, WS 7

Gates which agents can do what:

```kotlin
enum class AgentCapability {
    SHELL_EXEC, PYTHON_REPL, NODE_REPL,
    FILE_WRITE, FILE_READ,
    NETWORK_UNRESTRICTED, NETWORK_ALLOWLIST, NETWORK_BLOCKED,
    SNAPSHOT_ROLLBACK, RUN_ONCE, INSTALL_PACKAGES
}

data class AgentProfile(
    val name: String,
    val capabilities: Set<AgentCapability>,
    val egressPolicy: EgressPolicy,
    val seccompPolicy: SeccompPolicy,
    val restartPolicy: RestartPolicy,
    val maxConcurrentSessions: Int = 3
)
```

### Dependency DAG

```
[WS 4 Events] ──┬──> [WS 5 RunOnce]
                ├──> [WS 8 HTTP API]
                └──> [WS 11 Ring Buffer]

[WS 2 Bootstrap] ──┬──> [WS 1/10 REPLs]
                   ├──> [WS 3 COW FS]
                   ├──> [WS 6 Egress]
                   └──> [WS 7 Seccomp]

[WS 12 PTY FDs] ───> [WS 7 Seccomp]

[WS 9 Restart] ───> [WS 4 Events]

[WS 13 Caps] ───> [WS 2, 6, 7]
```

### Summary Table

| # | Workstream | Phase | Size | Parallelizable With |
|---|---|---|---|---|
| 4 | Structured Events | 0 | M | 11, 12 |
| 11 | Ring Buffer | 0 | M | 4, 12 |
| 12 | Separate PTY FDs | 0 | M | 4, 11 |
| 2 | Pluggable Bootstrap | 1 | S | 9 |
| 9 | Restart Policies | 1 | S | 2 |
| 1/10 | REPL Sessions | 2 | M | 3, 5 |
| 3 | COW Filesystem | 2 | L | 1/10, 5 |
| 5 | RunOnce API | 2 | S | 1/10, 3 |
| 6 | Egress Control | 3 | L | 7 |
| 7 | Seccomp Filter | 3 | M | 6 |
| 8 | HTTP/JSON API | 4 | M | 13 |
| 13 | Capability Matrix | 4 | S | 8 |

---

## 5. Known Issues To Address First

Before building on top of the roadmap, fix the foundation:

1. **Run `./gradlew :app:assembleDebug` on a clean checkout.** Nothing in
   this codebase has been verified end-to-end. First build will likely
   surface issues with `jniLibs` packaging, `libpython_shell.so` ELF type,
   or missing tool binaries.

2. **Verify bash actually boots from the APK.** Install on device, open
   a session, confirm the `bash` symlink in `usr/bin/` points to
   `libbash.so` from `nativeLibDir` and executes successfully.

3. **Verify TUI bridge receives full-width output.** Run `htop` via the
   terminal; check that `shadowEmulator` has non-truncated content.
   This requires the PTY TIOCSWINSZ pin (documented above as unshipped).

4. **Clean up SYSTEM-DESIGN.md / SYSTEM-STATE.md / SANDBOX-PLAN.md** if
   they duplicate this document. This file is the source of truth.

---

## 6. What "Done" Looks Like

A user installs the APK and:

1. Opens the app — sees a real bash shell, dark theme, cursor blinking
2. Types `python3 -c "print('hi')"` — sees `hi`
3. Runs `npm install` in a project — works, no crashes
4. Runs `htop` — sees a scrollable process list rendered as native Compose
   widgets (not a squished 40-col mess)
5. Switches apps for 10 minutes — returns to find the shell still running
   at the same prompt
6. Sends an AI agent (Claude Code / Codex / Gemini) a task via the HTTP
   API — agent runs commands in an isolated session with egress allowlist
   and seccomp sandbox, returns structured output
