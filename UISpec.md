Cory Mobile — UI Architecture Spec
Overview
Cory runs AI coding agents (Claude Code, Codex, Gemini) locally on Android. The agents' TUIs are designed for 80-120 column desktop terminals and render poorly on phone-width screens. Our approach: run the agent in a hidden full-width PTY and present a mobile-native Compose UI that we control.

Architecture
┌─────────────────────────────────────────────┐
│                  Agent Process               │
│  (claude / codex / gemini-cli)              │
│  Thinks it's in a 120x40 terminal           │
└──────────────────┬──────────────────────────┘
                   │ PTY (hidden, 120 cols)
                   ▼
┌─────────────────────────────────────────────┐
│              OutputProcessor                 │
│                                             │
│  Parses agent output stream and extracts:   │
│  - Agent text (explanations, questions)     │
│  - Tool calls (file read, edit, search)     │
│  - Shell commands + their stdout/stderr     │
│  - Diffs (file edits)                       │
│  - Status (thinking, running, done, error)  │
│  - Exit codes                               │
└──────────────────┬──────────────────────────┘
                   │ Structured data
                   ▼
┌─────────────────────────────────────────────┐
│           Compose UI (our rendering)         │
│                                             │
│  Agent text    → Text() with word wrap      │
│  Commands      → collapsible monospace block│
│  Diffs         → syntax-highlighted spans   │
│  Progress      → native indicator           │
│  Errors        → red block, auto-expanded   │
└─────────────────────────────────────────────┘

Device Layouts
Two layouts, one breakpoint. Use SupportingPaneScaffold from Material3 Adaptive.

Phone / Foldable Closed (single pane)
Only the parsed mobile-friendly view is shown. The raw terminal PTY runs offscreen.

┌────────────────────────────────────┐
│  Cory    [1] [2] [+]         ⚙️   │
├────────────────────────────────────┤
│                                    │
│  > clone acme/webapp and run it    │
│                                    │
│  Cloning repo and setting up...    │
│                                    │
│  ┌ git clone ──────────── ✓ 0.8s ┐ │
│  │ Cloning into 'webapp'...      │ │
│  │ done.                         │ │
│  └───────────────────────────────┘ │
│                                    │
│  ┌ npm install ───────── ✓ 34s ──┐ │
│  │ added 847 packages            │ │
│  │ 12 vulnerabilities            │ │
│  └───────────────────────────────┘ │
│                                    │
│  ┌ npm run dev ──────── ✓ 2.1s ──┐ │
│  │ ➜ localhost:3000              │ │
│  └───────────────────────────────┘ │
│                                    │
│  App is running. Found 12 vuln     │
│  (none critical). Fix them?        │
│                                    │
├────────────────────────────────────┤
│  What should I do next...      ▶  │
└────────────────────────────────────┘

User prompt at top as plain text
Agent prose is collapsed: first 2 lines shown, "⤷ N more lines" for the rest, tappable to expand
Command blocks show: command name, status icon (⏳🟢🔴), duration, last 1-2 lines of output
Tapping a command block expands to full scrollable output
Failed commands (🔴) auto-expand to show the error
Bottom input bar is always visible
Session tabs at top: [1] [2] [+] for multiple agent sessions
Foldable Open / Tablet (dual pane)
Left: the parsed mobile-friendly view (primary). Right: the raw terminal at full width (supporting).

┌──────────────────────────┬─────────────────────────┐
│  Cory                ⚙️   │  Terminal (raw)     ⋮   │
├──────────────────────────┼─────────────────────────┤
│                          │                         │
│  > fix the failing test  │  ╭─────────────────╮    │
│                          │  │ > fix the test  │    │
│  ┌ edit App.test.tsx ─┐  │  ╰─────────────────╯    │
│  │ - user={mock}      │  │                         │
│  │ + <App />          │  │  ● Reading App.test.tsx │
│  └────────────────────┘  │                         │
│                          │  ● Editing App.test.tsx │
│  ┌ npm test ──── ✓ ───┐  │    @@ -4,7 +4,7 @@      │
│  │ 23 passed          │  │    - render(<App        │
│  └────────────────────┘  │      user={mock} />)    │
│                          │    + render(<App />)    │
│  Fixed. Test was using   │                         │
│  a removed prop.         │  ● bash npm test        │
│                          │    Tests: 23 passed     │
│                          │    ✓ All passing        │
│                          │                         │
├──────────────────────────┤                         │
│ What should I do...  ▶  │                         │
└──────────────────────────┴─────────────────────────┘
         60%                        40%

Left pane: same as phone layout
Right pane: CanvasTerminalView showing the actual 120-col PTY output, scrollable
Right pane is for power users / debugging — most users will ignore it
SupportingPaneScaffold handles fold/unfold transitions automatically
When folded, right pane disappears, left pane goes full-screen
UI Components to Build
ui/
├── CoryScaffold.kt              # SupportingPaneScaffold wrapper
│                                  # handles phone ↔ foldable transition
│
├── AgentView.kt                 # Main pane: scrollable list of parsed blocks
│                                  # LazyColumn of AgentTextBlock, CommandBlock, etc.
│
├── blocks/
│   ├── AgentTextBlock.kt        # Agent prose, collapsible ("⤷ N more lines")
│   ├── CommandBlock.kt          # Shell command: icon + name + duration + output
│   ├── DiffBlock.kt             # File edit: syntax-highlighted diff
│   ├── UserPromptBlock.kt       # User's input (styled differently)
│   └── ErrorBlock.kt            # Failed command, auto-expanded
│
├── TerminalPane.kt              # Right pane: raw CanvasTerminalView
│
├── InputBar.kt                  # Bottom text field + send button
│                                  # always visible, fixed to bottom
│
└── SessionTabs.kt               # [1] [2] [+] tab bar at top

Data Model
sealed class AgentBlock {
    data class UserPrompt(val text: String) : AgentBlock()
    data class AgentText(
        val text: String,
        val isCollapsed: Boolean = true
    ) : AgentBlock()
    data class Command(
        val command: String,
        val output: String,
        val exitCode: Int?,          // null = still running
        val durationMs: Long?,
        val isExpanded: Boolean = false
    ) : AgentBlock()
    data class Diff(
        val filePath: String,
        val hunks: List<DiffHunk>
    ) : AgentBlock()
}

OutputProcessor Parsing Strategy
The agents use recognizable patterns. We pattern-match on these to extract AgentBlocks:

Agent	Command marker	Tool call marker	Diff marker
Claude Code	❯ or $ prefix	● tool_name	@@ -N,N +N,N @@
Codex	$ prefix	Function call JSON	Unified diff format
Gemini	$ prefix	Tool use blocks	Unified diff format
The parser doesn't need to be perfect. Anything it can't classify becomes AgentText. Worst case, the user sees unformatted agent prose — still readable, just not as pretty.

What Already Exists (from OperitTerminalCore)
Component	Status	Notes
pty.c / Pty.kt	Done	Fork+exec, CLOEXEC, parameterized window size
CanvasTerminalView	Done	Used for the raw terminal pane on foldable
AnsiTerminalEmulator	Done	Feeds the raw terminal pane
OutputProcessor	Exists, needs extension	Currently parses ANSI for the raw view. Needs a second path that extracts AgentBlocks
TerminalManager	Done	Session lifecycle, I/O routing
SessionManager	Done	Multi-session state
TerminalBootstrap	Done	Sets up bash, busybox, PATH
TerminalService	Exists	Needs startForeground() to prevent killing
What Needs Building
Component	Effort	Priority
AgentBlock data model	Small	P0
OutputProcessor agent-aware parsing	Medium	P0
AgentView (LazyColumn of blocks)	Medium	P0
CommandBlock / AgentTextBlock composables	Medium	P0
InputBar	Small	P0
SessionTabs	Small	P0
CoryScaffold (SupportingPaneScaffold)	Small	P1
DiffBlock with syntax highlighting	Medium	P2
Foreground Service notification	Small	P0
Dependencies
// Material3 Adaptive (for SupportingPaneScaffold)
implementation("androidx.compose.material3.adaptive:adaptive:1.1.0")
implementation("androidx.compose.material3.adaptive:adaptive-layout:1.1.0")
implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.1.0")

Key Design Decisions
Hidden PTY at 120 cols — the agent gets a proper terminal, we get structured output to parse
No chat bubbles — this isn't a chat app. It's a command interface with parsed output blocks.
Collapse agent prose by default — nobody reads 20 lines of "I'll help you with that, let me start by examining the project structure..." Show 2 lines, fold the rest.
Commands are first-class — always visible, never collapsed, show status + exit code
Errors auto-expand — if something fails, show it immediately, don't make the user tap
Raw terminal on foldable only — phone users get the parsed view. Power users on foldable get both.
Foreground Service — non-negotiable for keeping the process alive

1 step
1 step

master

claude/review-android-pseudoterminal-Eernm

+950
-2521

View PR
