package com.ai.assistance.operit.terminal.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator
import com.ai.assistance.operit.terminal.provider.type.TerminalType
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import java.io.OutputStreamWriter
import java.util.UUID

data class QueuedCommand(
    val id: String,
    val command: String
)

class CommandHistoryItem(
    val id: String,
    prompt: String,
    command: String,
    output: String,
    isExecuting: Boolean = false
) {
    private var _prompt by mutableStateOf(prompt)
    private var _command by mutableStateOf(command)
    private var _output by mutableStateOf(output)
    private var _isExecuting by mutableStateOf(isExecuting)
    
    val outputPages = mutableStateListOf<String>()
    
    // AIDLgetter
    val prompt: String get() = _prompt
    val command: String get() = _command
    val output: String get() = _output
    val isExecuting: Boolean get() = _isExecuting
    
    // UIsetter
    fun setPrompt(value: String) { _prompt = value }
    fun setCommand(value: String) { _command = value }
    fun setOutput(value: String) { _output = value }
    fun setExecuting(value: Boolean) { _isExecuting = value }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CommandHistoryItem
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun toString(): String {
        return "CommandHistoryItem(id='$id', prompt='$prompt', command='$command', output='${output.take(20)}...', isExecuting=$isExecuting)"
    }
}

enum class SessionInitState {
    INITIALIZING,
    LOGGED_IN,
    AWAITING_FIRST_PROMPT,
    READY
}

data class TerminalSessionData(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val terminalType: TerminalType = TerminalType.LOCAL,
    val terminalSession: com.ai.assistance.operit.terminal.TerminalSession? = null,
    val pty: com.ai.assistance.operit.terminal.Pty? = null, // PTY
    val sessionWriter: OutputStreamWriter? = null,
    val currentDirectory: String = "$ ",
    @Transient val currentCommandOutput: StringBuilder = StringBuilder(),
    @Transient val rawBuffer: StringBuilder = StringBuilder(),
    val isWaitingForInteractiveInput: Boolean = false,
    val lastInteractivePrompt: String = "",
    val isInteractiveMode: Boolean = false,
    val interactivePrompt: String = "",
    val initState: SessionInitState = SessionInitState.INITIALIZING,
    val readJob: Job? = null,
    val isFullscreen: Boolean = false,
    @Transient val ansiParser: AnsiTerminalEmulator = AnsiTerminalEmulator(),
    @Transient var currentExecutingCommand: CommandHistoryItem? = null,
    @Transient var currentCommandStartedAtMs: Long? = null,
    @Transient var currentOutputLineCount: Int = 0,
    @Transient val commandQueue: MutableList<QueuedCommand> = mutableListOf(),
    @Transient val commandMutex: Mutex = Mutex(),
    var scrollOffsetY: Float = 0f
) {
    val isInitializing: Boolean
        get() = initState != SessionInitState.READY
}

data class TerminalState(
    val sessions: List<TerminalSessionData> = emptyList(),
    val currentSessionId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
) {
    val currentSession: TerminalSessionData?
        get() = currentSessionId?.let { sessionId ->
            sessions.find { it.id == sessionId }
        }
}

enum class PackageManagerType(val displayName: String) {
    APT("APT"),
    PIP("Pip/Uv"),
    NPM("NPM"),
    RUST("Rust/Cargo")
}

@Serializable
data class MirrorSource(
    val id: String,
    val name: String,
    val url: String,
    val isHttps: Boolean = false
)

data class SourceConfig(
    val packageManager: PackageManagerType,
    val selectedSourceId: String,
    val sources: List<MirrorSource>
) 
