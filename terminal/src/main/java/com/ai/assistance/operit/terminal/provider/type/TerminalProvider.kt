package com.ai.assistance.operit.terminal.provider.type

import com.ai.assistance.operit.terminal.Pty
import com.ai.assistance.operit.terminal.TerminalSession

data class HiddenExecResult(
    val output: String,
    val exitCode: Int,
    val state: State = State.OK,
    val error: String = "",
    val rawOutputPreview: String = ""
) {
    enum class State {
        OK,
        SHELL_START_FAILED,
        SHELL_NOT_READY,
        PROCESS_EXITED,
        MISSING_BEGIN_MARKER,
        MISSING_END_MARKER,
        INVALID_EXIT_CODE,
        TIMEOUT,
        EXECUTION_ERROR
    }

    val isOk: Boolean
        get() = state == State.OK
}

/**
 *
 * 
 * SSH
 */
interface TerminalProvider {
    
    /**
     * /
     */
    suspend fun isConnected(): Boolean
    
    /**
     * SSH
     */
    suspend fun connect(): Result<Unit>
    
        suspend fun disconnect()
    
    /**
     *
     * 
     * @param sessionId ID
     * @return PTY
     */
    suspend fun startSession(sessionId: String): Result<Pair<TerminalSession, Pty>>
    
    /**
     *
     * 
     * @param sessionId ID
     */
    suspend fun closeSession(sessionId: String)

    /**
     * Execute a command in a hidden (non-interactive) context.
     */
    suspend fun executeHiddenCommand(
        command: String,
        executorKey: String = "default",
        timeoutMs: Long = 120000L
    ): HiddenExecResult
    
    /**
     *
     * 
     * @return 
     */
    suspend fun getWorkingDirectory(): String
    
    /**
     *
     * 
     * @return 
     */
    fun getEnvironment(): Map<String, String>
}

enum class TerminalType {
    /**
     * Local native shell
     */
    LOCAL,
    
    /**
     * SSH 
     */
    SSH,
    
    /**
     * ADB 
     */
    ADB
}

