package com.ai.assistance.operit.terminal

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.data.SessionInitState
import com.ai.assistance.operit.terminal.provider.type.LocalTerminalProvider
import com.ai.assistance.operit.terminal.provider.type.TerminalType
import com.ai.assistance.operit.terminal.view.domain.OutputProcessor
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.util.UUID

/**
 * Central coordinator for terminal sessions.
 *
 * Manages session lifecycle, I/O routing, and provides reactive state
 * for the Compose UI layer.
 */
class TerminalManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TerminalManager"

        @Volatile
        private var instance: TerminalManager? = null

        fun getInstance(context: Context): TerminalManager =
            instance ?: synchronized(this) {
                instance ?: TerminalManager(context.applicationContext).also { instance = it }
            }
    }

    // ---- public coroutine scope (used by TerminalEnv / TerminalService) ----
    val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ---- bootstrap state ----
    private val _bootstrapComplete = MutableStateFlow(false)
    val bootstrapComplete: StateFlow<Boolean> = _bootstrapComplete.asStateFlow()

    // ---- reactive state ----
    private val _sessions = MutableStateFlow<List<TerminalSessionData>>(emptyList())
    val sessions: StateFlow<List<TerminalSessionData>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _currentDirectory = MutableStateFlow("$ ")
    val currentDirectory: StateFlow<String> = _currentDirectory.asStateFlow()

    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()

    private val _terminalEmulator = MutableStateFlow(AnsiTerminalEmulator(screenWidth = 80, screenHeight = 24))
    val terminalEmulator: StateFlow<AnsiTerminalEmulator> = _terminalEmulator.asStateFlow()

    /** Full terminal state (used by TerminalScreen). */
    val terminalState: StateFlow<com.ai.assistance.operit.terminal.data.TerminalState>
        get() = sessionManager.state

    // ---- AIDL events ----
    private val _commandExecutionEvents = MutableSharedFlow<CommandExecutionEvent>(extraBufferCapacity = 64)
    val commandExecutionEvents: SharedFlow<CommandExecutionEvent> = _commandExecutionEvents.asSharedFlow()

    private val _directoryChangeEvents = MutableSharedFlow<SessionDirectoryEvent>(extraBufferCapacity = 16)
    val directoryChangeEvents: SharedFlow<SessionDirectoryEvent> = _directoryChangeEvents.asSharedFlow()

    // Engineer additions: structured I/O events for the parsed UI layer
    private val _userInputEvents = MutableSharedFlow<UserInputEvent>(extraBufferCapacity = 64)
    val userInputEvents: SharedFlow<UserInputEvent> = _userInputEvents.asSharedFlow()

    private val _sessionOutputEvents = MutableSharedFlow<SessionOutputEvent>(extraBufferCapacity = 128)
    val sessionOutputEvents: SharedFlow<SessionOutputEvent> = _sessionOutputEvents.asSharedFlow()

    // ---- internal bookkeeping ----
    private val sessionManager = SessionManager(this)
    private val localProvider = LocalTerminalProvider(context)
    private val outputProcessor = OutputProcessor(
        onCommandExecutionEvent = { event -> coroutineScope.launch { _commandExecutionEvents.emit(event) } },
        onDirectoryChangeEvent = { event ->
            coroutineScope.launch {
                _directoryChangeEvents.emit(event)
                _currentDirectory.value = event.currentDirectory
            }
        },
        onCommandCompleted = { /* no-op for now */ }
    )

    private val scrollOffsets = mutableMapOf<String, Float>()

    init {
        // Block session creation until bootstrap completes.
        coroutineScope.launch(Dispatchers.IO) {
            try {
                TerminalBootstrap.ensureEnvironment(context)
            } catch (e: Exception) {
                Log.e(TAG, "Bootstrap failed", e)
            } finally {
                _bootstrapComplete.value = true
            }
        }
        // Mirror SessionManager state into our StateFlows.
        coroutineScope.launch {
            sessionManager.state.collect { state ->
                _sessions.value = state.sessions
                _currentSessionId.value = state.currentSessionId
                val current = state.currentSession
                if (current != null) {
                    _terminalEmulator.value = current.ansiParser
                }
            }
        }
    }

    // ---- Session lifecycle ----

    /**
     * Create a new terminal session.
     *
     * Suspends until bootstrap is complete before attempting to launch bash.
     * Only inserts session into state after provider succeeds.
     */
    suspend fun createNewSession(): TerminalSessionData {
        // Wait for bootstrap to finish before trying to exec bash.
        _bootstrapComplete.first { it }

        return withContext(Dispatchers.IO) {
            val tempData = TerminalSessionData(
                title = "Shell ${(_sessions.value.size) + 1}",
                terminalType = TerminalType.LOCAL
            )

            // Try provider first. If this throws, no orphan session in state.
            val (terminalSession, pty) = localProvider.startSession(tempData.id).getOrThrow()

            val writer = OutputStreamWriter(terminalSession.stdin, Charsets.UTF_8)
            val readJob = startOutputReader(tempData.id, terminalSession)

            val sessionData = tempData.copy(
                terminalSession = terminalSession,
                pty = pty,
                sessionWriter = writer,
                readJob = readJob,
                initState = SessionInitState.READY
            )

            // Now insert the fully-initialized session.
            sessionManager.insertSession(sessionData)
            sessionData
        }
    }

    fun switchToSession(sessionId: String) {
        sessionManager.switchToSession(sessionId)
    }

    fun closeSession(sessionId: String) {
        sessionManager.closeSession(sessionId)
    }

    internal fun closeTerminalSession(sessionId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            localProvider.closeSession(sessionId)
        }
    }

    internal fun onSessionClosed(sessionId: String) {
        scrollOffsets.remove(sessionId)
    }

    // ---- I/O ----

    /**
     * Send a newline-terminated command (e.g. from the input bar).
     * Returns true if the command was actually written, false otherwise.
     */
    suspend fun sendCommand(command: String): Boolean {
        val session = sessionManager.getCurrentSession() ?: return false
        val writer = session.sessionWriter ?: return false

        // Track the command for the parsed UI
        val commandId = UUID.randomUUID().toString()
        session.currentExecutingCommand = com.ai.assistance.operit.terminal.data.CommandHistoryItem(
            id = commandId,
            prompt = session.currentDirectory,
            command = command,
            output = "",
            isExecuting = true
        )

        _userInputEvents.tryEmit(
            UserInputEvent(
                sessionId = session.id,
                text = command,
                isCommand = true
            )
        )

        withContext(Dispatchers.IO) {
            writer.write(command + "\n")
            writer.flush()
        }
        return true
    }

    /**
     * Send newline-terminated input for interactive prompts (sudo, y/n, etc.).
     * Returns true if written.
     */
    fun sendInput(input: String): Boolean {
        val session = sessionManager.getCurrentSession() ?: return false
        val writer = session.sessionWriter ?: return false
        _userInputEvents.tryEmit(
            UserInputEvent(
                sessionId = session.id,
                text = input,
                isCommand = false
            )
        )
        coroutineScope.launch(Dispatchers.IO) {
            writer.write(input + "\n")
            writer.flush()
        }
        return true
    }

    /**
     * Send raw bytes to the PTY without appending a newline.
     * Used by the canvas terminal view for keystroke-level I/O.
     */
    fun sendRawInput(data: String) {
        val session = sessionManager.getCurrentSession() ?: return
        val writer = session.sessionWriter ?: return
        _userInputEvents.tryEmit(
            UserInputEvent(
                sessionId = session.id,
                text = data,
                isCommand = false
            )
        )
        coroutineScope.launch(Dispatchers.IO) {
            writer.write(data)
            writer.flush()
        }
    }

    fun sendInterruptSignal() {
        val session = sessionManager.getCurrentSession() ?: return
        val writer = session.sessionWriter ?: return
        coroutineScope.launch(Dispatchers.IO) {
            writer.write("\u0003")
            writer.flush()
        }
    }

    // ---- Scroll offsets ----

    fun saveScrollOffset(sessionId: String, offset: Float) {
        scrollOffsets[sessionId] = offset
    }

    fun getScrollOffset(sessionId: String): Float = scrollOffsets[sessionId] ?: 0f

    // ---- Output reader ----

    /**
     * On EOF / error, marks the session as dead so the UI can react.
     */
    private fun startOutputReader(sessionId: String, terminalSession: TerminalSession): kotlinx.coroutines.Job {
        return coroutineScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val count = terminalSession.stdout.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        val text = String(buffer, 0, count, Charsets.UTF_8)
                        outputProcessor.processOutput(sessionId, text, sessionManager)
                        _sessionOutputEvents.tryEmit(SessionOutputEvent(sessionId = sessionId, chunk = text))
                    }
                }
            } catch (e: Exception) {
                if (e !is java.io.IOException) {
                    Log.e(TAG, "Output reader error for session $sessionId", e)
                }
            }

            // PTY exited — get exit code and mark session dead.
            Log.d(TAG, "Output reader finished for session $sessionId")
            val exitCode = try {
                terminalSession.process.exitValue()
            } catch (_: Exception) {
                -1
            }
            sessionManager.updateSession(sessionId) { s ->
                s.copy(
                    initState = SessionInitState.INITIALIZING,
                    readJob = null
                )
            }
            _commandExecutionEvents.tryEmit(
                CommandExecutionEvent(
                    commandId = "exit",
                    command = "",
                    sessionId = sessionId,
                    outputChunk = "Terminal exited with code $exitCode",
                    isCompleted = true,
                    exitCode = exitCode
                )
            )
        }
    }
}

/** Suspend until a StateFlow emits a value matching [predicate]. */
private suspend fun <T> StateFlow<T>.first(predicate: (T) -> Boolean): T {
    if (predicate(value)) return value
    return kotlinx.coroutines.flow.first(predicate)
}
