package com.ai.assistance.operit.terminal.view.domain

import android.util.Log
import com.ai.assistance.operit.terminal.CommandExecutionEvent
import com.ai.assistance.operit.terminal.SessionDirectoryEvent
import com.ai.assistance.operit.terminal.SessionManager
import com.ai.assistance.operit.terminal.data.SessionInitState
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiUtils

/**
 *
 * @property justHandledCarriageReturn CR true
 */
private data class SessionProcessingState(
    var justHandledCarriageReturn: Boolean = false
)

class OutputProcessor(
    private val onCommandExecutionEvent: (CommandExecutionEvent) -> Unit = {},
    private val onDirectoryChangeEvent: (SessionDirectoryEvent) -> Unit = {},
    private val onCommandCompleted: (String) -> Unit = {}
) {

    private val sessionStates = mutableMapOf<String, SessionProcessingState>()

    companion object {
        private const val TAG = "OutputProcessor"
        private const val MAX_LINES_PER_HISTORY_ITEM = 10

        private const val MAX_RAW_BUFFER_CHARS = 256 * 1024
        private const val MAX_OUTPUT_PAGES_PER_COMMAND = 100
    }

    fun processOutput(
        sessionId: String,
        chunk: String,
        sessionManager: SessionManager
    ) {
        val session = sessionManager.getSession(sessionId) ?: return
        session.rawBuffer.append(chunk)

        if (session.rawBuffer.length > MAX_RAW_BUFFER_CHARS) {
            val over = session.rawBuffer.length - MAX_RAW_BUFFER_CHARS
            session.rawBuffer.delete(0, over)
        }

        Log.d(TAG, "Processing chunk for session $sessionId. New buffer size: ${session.rawBuffer.length}")

        if (detectFullscreenMode(sessionId, session.rawBuffer, sessionManager)) {
            return
        }

        // ANSI Canvas
        session.ansiParser.parse(chunk)
        
        if (session.isFullscreen) {
            // ansiParser
            return
        }

        val state = sessionStates.getOrPut(sessionId) { SessionProcessingState() }

        while (session.rawBuffer.isNotEmpty()) {
            val bufferContent = session.rawBuffer.toString()
            val newlineIndex = bufferContent.indexOf('\n')
            val carriageReturnIndex = bufferContent.indexOf('\r')

            if (carriageReturnIndex != -1 && (newlineIndex == -1 || carriageReturnIndex < newlineIndex)) {
                // We have a carriage return.
                val line = bufferContent.substring(0, carriageReturnIndex)

                val isCRLF = carriageReturnIndex + 1 < bufferContent.length && bufferContent[carriageReturnIndex + 1] == '\n'
                val consumedLength = if (isCRLF) carriageReturnIndex + 2 else carriageReturnIndex + 1

                session.rawBuffer.delete(0, consumedLength)

                if (isCRLF) {
                    // It's a CRLF, treat as a normal line. `processLine` will handle the
                    // case where this CRLF finalizes a progress-updated line.
                    Log.d(TAG, "Processing CRLF line: '$line'")
                    processLine(sessionId, line, sessionManager)
                } else {
                    // It's just CR, treat as a progress update.
                    Log.d(TAG, "Processing CR line: '$line'")
                    handleCarriageReturn(sessionId, line, sessionManager)
                }
            } else if (newlineIndex != -1) {
                // We have a newline without a preceding carriage return.
                val line = bufferContent.substring(0, newlineIndex)
                session.rawBuffer.delete(0, newlineIndex + 1)
                Log.d(TAG, "Processing LF line: '$line'")
                processLine(sessionId, line, sessionManager)
            } else {
                // No full line-terminator found in the buffer.
                
                if (AnsiUtils.isProgressLine(bufferContent)) {
                    Log.d(TAG, "Detected progress line in buffer: '$bufferContent'")
                    val cleanContent = AnsiUtils.stripAnsi(bufferContent)
                    Log.d(TAG, "Stripped progress line: '$cleanContent'")
                    handleCarriageReturn(sessionId, bufferContent, sessionManager)
                    session.rawBuffer.clear()
                    continue // Re-check buffer in case more data came in
                }
                
                val cleanContent = AnsiUtils.stripAnsi(bufferContent)
                
                // shell
                val isShellPrompt = isPrompt(cleanContent)
                
                // PTY
                val isWaitingInput = isInteractivePrompt(cleanContent, sessionId, sessionManager)
                
                if (isShellPrompt || isWaitingInput) {
                    Log.d(TAG, "Processing remaining buffer as interactive/shell prompt: '$bufferContent'")
                    // Since this is not a newline-terminated line, the justHandledCarriageReturn
                    // state from a previous CR is not relevant here. We reset it to ensure
                    // the prompt is processed correctly by handleReadyState.
                    state.justHandledCarriageReturn = false
                    
                    // shell
                    if (isWaitingInput && !isShellPrompt) {
                        handleInteractivePrompt(sessionId, cleanContent, sessionManager)
                    } else {
                        processLine(sessionId, bufferContent, sessionManager)
                    }
                    session.rawBuffer.clear()
                }
                break // Exit loop, wait for more data.
            }
        }
    }

    fun clearSessionState(sessionId: String) {
        sessionStates.remove(sessionId)
    }

    private fun handleCarriageReturn(sessionId: String, line: String, sessionManager: SessionManager) {
        val cleanLine = AnsiUtils.stripAnsi(line)
        val session = sessionManager.getSession(sessionId) ?: return
        if (session.initState != SessionInitState.READY) {
            processLine(sessionId, line, sessionManager)
            return
        }
        
        // CR line
        if (isPrompt(cleanLine.trim())) {
            Log.d(TAG, "Detected prompt in CR line: '$cleanLine'")
            handlePrompt(sessionId, cleanLine, sessionManager)
            sessionStates[sessionId]?.justHandledCarriageReturn = false
            return
        }
        
        // ANSI
        if (cleanLine.isNotEmpty()) {
            updateProgressOutput(sessionId, cleanLine, sessionManager)
            sessionStates[sessionId]?.justHandledCarriageReturn = true
        }
        // ANSI justHandledCarriageReturn
    }

    private fun processLine(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        val session = sessionManager.getSession(sessionId) ?: return

        when (session.initState) {
            SessionInitState.INITIALIZING -> {
                handleInitializingState(sessionId, line, sessionManager)
            }
            SessionInitState.LOGGED_IN -> {
                handleLoggedInState(sessionId, line, sessionManager)
            }
            SessionInitState.AWAITING_FIRST_PROMPT -> {
                handleAwaitingFirstPromptState(sessionId, line, sessionManager)
            }
            SessionInitState.READY -> {
                val state = sessionStates.getOrPut(sessionId) { SessionProcessingState() }
                if (state.justHandledCarriageReturn) {
                    // A newline is received after a carriage return. This finalizes the line that was being updated.
                    state.justHandledCarriageReturn = false // Reset state immediately

                    val cleanLine = AnsiUtils.stripAnsi(line)

                    if (cleanLine.isNotEmpty()) {
                        updateProgressOutput(sessionId, cleanLine, sessionManager)
                    }

                    // Always append a newline to finalize the line and move to the next.
                    val currentItem = session.currentExecutingCommand
                    if (currentItem != null) {
                        session.currentCommandOutput.append('\n')
                        currentItem.setOutput(session.currentCommandOutput.toString())
                    }
                } else {
                    handleReadyState(sessionId, line, sessionManager)
                }
            }
        }
    }

    private fun handleInitializingState(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        if (line.contains("LOGIN_SUCCESSFUL")) {
            Log.d(TAG, "Login successful marker found.")
            sessionManager.getSession(sessionId)?.let { session ->
                session.currentCommandOutput.clear()
                sessionManager.updateSession(sessionId) {
                    it.copy(initState = SessionInitState.LOGGED_IN)
                }
            }
        }
    }

    private fun handleLoggedInState(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        if (AnsiUtils.stripAnsi(line).contains("TERMINAL_READY")) {
            Log.d(TAG, "TERMINAL_READY marker found.")
            sessionManager.updateSession(sessionId) { session ->
                session.copy(initState = SessionInitState.AWAITING_FIRST_PROMPT)
            }
        }
    }

    private fun handleAwaitingFirstPromptState(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        val cleanLine = AnsiUtils.stripAnsi(line)
        Log.d(TAG, "handleAwaitingFirstPromptState: checking line: '$cleanLine'")
        if (handlePrompt(sessionId, cleanLine, sessionManager)) {
            Log.d(TAG, "First prompt detected. Session is now ready.")
            sessionManager.updateSession(sessionId) { session ->
                session.copy(initState = SessionInitState.READY)
            }
            
            // Canvas
            sendWelcomeMessage(sessionId, sessionManager)
        } else {
            Log.d(TAG, "Not a prompt, continuing to wait...")
        }
    }

    private fun handleReadyState(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ) {
        val cleanLine = AnsiUtils.stripAnsi(line)
        Log.d(TAG, "Stripped line: '$cleanLine'")

        // TERMINAL_READY
        if (cleanLine.trim() == "TERMINAL_READY") {
            return
        }

        val session = sessionManager.getSession(sessionId) ?: return

        if (isCommandEcho(cleanLine, session)) {
            Log.d(TAG, "Ignoring command echo: '$cleanLine'")
            return
        }

        if (handlePrompt(sessionId, cleanLine, sessionManager)) {
            return
        }

        // CRLF
        // buffer 118

        updateCommandOutput(sessionId, cleanLine, sessionManager)
    }

    fun isPrompt(line: String): Boolean {
        val cwdPromptRegex = Regex("<cwd>(.*)</cwd>.*[#$]")
        if (cwdPromptRegex.containsMatchIn(line)) {
            return true
        }

        val trimmed = line.trim()
        return trimmed.endsWith("$") ||
                trimmed.endsWith("#") ||
                trimmed.endsWith("$ ") ||
                trimmed.endsWith("# ") ||
                Regex(".*@[a-zA-Z0-9.\\-]+\\s?:\\s?~?/?.*[#$]\\s*$").matches(trimmed) ||
                Regex("root@[a-zA-Z0-9.\\-]+:\\s?~?/?.*#\\s*$").matches(trimmed)
    }

    private fun handlePrompt(
        sessionId: String,
        line: String,
        sessionManager: SessionManager
    ): Boolean {
        val session = sessionManager.getSession(sessionId) ?: return false

        val cwdPromptRegex = Regex("<cwd>(.*)</cwd>.*[#$]")
        val match = cwdPromptRegex.find(line)

        val isAPrompt = if (match != null) {
            val path = match.groups[1]?.value?.trim() ?: "~"
            sessionManager.updateSession(sessionId) { session ->
                session.copy(currentDirectory = "$path $")
            }
            
            onDirectoryChangeEvent(SessionDirectoryEvent(
                sessionId = sessionId,
                currentDirectory = "$path $"
            ))
            
            Log.d(TAG, "Matched CWD prompt. Path: $path")

            val outputBeforePrompt = line.substring(0, match.range.first)
            if (outputBeforePrompt.isNotBlank()) {
                session.currentCommandOutput.append(outputBeforePrompt)
            }
            true
        } else {
            val trimmed = line.trim()
            val isFallbackPrompt = trimmed.endsWith("$") ||
                    trimmed.endsWith("#") ||
                    trimmed.endsWith("$ ") ||
                    trimmed.endsWith("# ") ||
                    Regex(".*@[a-zA-Z0-9.\\-]+\\s?:\\s?~?/?.*[#$]\\s*$").matches(trimmed) ||
                    Regex("root@[a-zA-Z0-9.\\-]+:\\s?~?/?.*#\\s*$").matches(trimmed)

            if (isFallbackPrompt) {
                val regex = Regex(""".*:\s*(~?/?.*)\s*[#$]$""")
                val matchResult = regex.find(trimmed)
                val cleanPrompt = matchResult?.groups?.get(1)?.value?.trim() ?: trimmed
                sessionManager.updateSession(sessionId) { session ->
                    session.copy(currentDirectory = "${cleanPrompt} $")
                }
                
                onDirectoryChangeEvent(SessionDirectoryEvent(
                    sessionId = sessionId,
                    currentDirectory = "${cleanPrompt} $"
                ))
                
                Log.d(TAG, "Matched fallback prompt: $cleanPrompt")
                true
            } else {
                false
            }
        }

        if (isAPrompt) {
            // shell
            if (session.isInteractiveMode) {
                sessionManager.updateSession(sessionId) {
                    it.copy(
                        isInteractiveMode = false,
                        interactivePrompt = ""
                    )
                }
            }
            finishCurrentCommand(sessionId, sessionManager)
            return true
        }
        return false
    }

    /**
     *  PTY 
     *  PTY 
     */
    fun isInteractivePrompt(line: String, sessionId: String, sessionManager: SessionManager): Boolean {
        val session = sessionManager.getSession(sessionId) ?: return false
        
        // shell
        if (session.currentExecutingCommand?.isExecuting != true) {
            return false
        }

        // The current PTY wrapper no longer exposes a mode-inspection API.
        // Until terminal-core restores that contract, treat interactive-prompt
        // detection as disabled instead of failing compilation.
        return false
    }

    private fun handleInteractivePrompt(
        sessionId: String,
        cleanLine: String,
        sessionManager: SessionManager
    ) {
        Log.d(TAG, "Detected interactive prompt: $cleanLine")
        val session = sessionManager.getSession(sessionId) ?: return
        
        sessionManager.updateSession(sessionId) { session ->
            session.copy(
                isWaitingForInteractiveInput = true,
                lastInteractivePrompt = cleanLine,
                isInteractiveMode = true,
                interactivePrompt = cleanLine
            )
        }

        if (cleanLine.isNotBlank()) {
            updateCommandOutput(sessionId, cleanLine, sessionManager)
        }
    }

    private fun isCommandEcho(cleanLine: String, session: TerminalSessionData): Boolean {
        val lastExecutingItem = session.currentExecutingCommand
        if (lastExecutingItem != null && lastExecutingItem.isExecuting) {
            val commandToCheck = lastExecutingItem.command.trim()
            val lineToCheck = cleanLine.trim()
            val isMatch = lineToCheck == commandToCheck

            if (session.currentCommandOutput.isEmpty() && isMatch) {
                return true
            }
        }
        return false
    }

    private fun updateCommandOutput(
        sessionId: String,
        cleanLine: String,
        sessionManager: SessionManager
    ) {
        val session = sessionManager.getSession(sessionId) ?: return
        val currentItem = session.currentExecutingCommand

        if (currentItem != null && currentItem.isExecuting) {
            val builder = session.currentCommandOutput
            if (builder.isNotEmpty() && builder.last() != '\n') {
                builder.append('\n')
            }
            builder.append(cleanLine)

            currentItem.setOutput(builder.toString())
            session.currentOutputLineCount++
            
            onCommandExecutionEvent(CommandExecutionEvent(
                commandId = currentItem.id,
                command = currentItem.command,
                sessionId = sessionId,
                outputChunk = cleanLine,
                isCompleted = false
            ))

            if (session.currentOutputLineCount >= MAX_LINES_PER_HISTORY_ITEM) {
                while (currentItem.outputPages.size >= MAX_OUTPUT_PAGES_PER_COMMAND) {
                    currentItem.outputPages.removeAt(0)
                }
                currentItem.outputPages.add(currentItem.output)
                builder.clear()
                session.currentOutputLineCount = 0
                currentItem.setOutput("")
            }
        }
    }

    private fun updateProgressOutput(
        sessionId: String,
        cleanLine: String,
        sessionManager: SessionManager
    ) {
        val session = sessionManager.getSession(sessionId) ?: return
        val builder = session.currentCommandOutput
        val lastExecutingItem = session.currentExecutingCommand

        if (lastExecutingItem != null && lastExecutingItem.isExecuting) {
             // More efficient way to replace the last line
            val lastNewlineIndex = builder.lastIndexOf('\n')
            if (lastNewlineIndex != -1) {
                // Found a newline, replace everything after it
                builder.setLength(lastNewlineIndex + 1)
                builder.append(cleanLine)
            } else {
                // No newline, replace the whole buffer
                builder.clear()
                builder.append(cleanLine)
            }
            // Update history from the builder
            lastExecutingItem.setOutput(builder.toString().trimEnd())
        }
    }

    private fun finishCurrentCommand(sessionId: String, sessionManager: SessionManager) {
        sessionManager.updateSession(sessionId) { session ->
            session.copy(
                isWaitingForInteractiveInput = false,
                lastInteractivePrompt = "",
                isInteractiveMode = false,
                interactivePrompt = ""
            )
        }

        val session = sessionManager.getSession(sessionId) ?: return
        val lastExecutingItem = session.currentExecutingCommand

        if (lastExecutingItem != null && lastExecutingItem.isExecuting) {
            val finalOutput = buildString {
                if (lastExecutingItem.outputPages.isNotEmpty()) {
                    append(lastExecutingItem.outputPages.joinToString("\n"))
                }
                val tail = session.currentCommandOutput.toString().trim()
                if (tail.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(tail)
                }
            }.trim()

            lastExecutingItem.setOutput(finalOutput)
            lastExecutingItem.setExecuting(false)

            Log.i(TAG, "Finishing command ${lastExecutingItem.id} for session $sessionId")
            
            onCommandExecutionEvent(CommandExecutionEvent(
                commandId = lastExecutingItem.id,
                command = lastExecutingItem.command,
                sessionId = sessionId,
                outputChunk = finalOutput,
                isCompleted = true,
                exitCode = inferExitCode(finalOutput),
                durationMs = session.currentCommandStartedAtMs?.let { System.currentTimeMillis() - it }
            ))

            // Clear the reference since command is no longer executing
            session.currentExecutingCommand = null
            session.currentCommandStartedAtMs = null
            session.currentCommandOutput.clear()
            
            onCommandCompleted(sessionId)
        }
    }

    fun handleSessionExit(
        sessionId: String,
        message: String,
        sessionManager: SessionManager
    ) {
        sessionManager.updateSession(sessionId) {
            it.copy(
                isWaitingForInteractiveInput = false,
                lastInteractivePrompt = "",
                isInteractiveMode = false,
                interactivePrompt = ""
            )
        }

        val session = sessionManager.getSession(sessionId) ?: return
        session.rawBuffer.clear()
        session.ansiParser.parse("\r\n$message\r\n")

        val lastExecutingItem = session.currentExecutingCommand
        if (lastExecutingItem != null && lastExecutingItem.isExecuting) {
            val builder = session.currentCommandOutput
            if (builder.isNotEmpty() && builder.last() != '\n') {
                builder.append('\n')
            }
            builder.append(message)

            val finalOutput = buildString {
                if (lastExecutingItem.outputPages.isNotEmpty()) {
                    append(lastExecutingItem.outputPages.joinToString("\n"))
                }
                val tail = builder.toString().trim()
                if (tail.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(tail)
                }
            }.trim()

            lastExecutingItem.setOutput(finalOutput)
            lastExecutingItem.setExecuting(false)

            onCommandExecutionEvent(
                CommandExecutionEvent(
                    commandId = lastExecutingItem.id,
                    command = lastExecutingItem.command,
                    sessionId = sessionId,
                    outputChunk = finalOutput,
                    isCompleted = true,
                    exitCode = inferExitCode(finalOutput),
                    durationMs = session.currentCommandStartedAtMs?.let { System.currentTimeMillis() - it }
                )
            )

            session.currentExecutingCommand = null
            session.currentCommandStartedAtMs = null
        }

        session.currentCommandOutput.clear()
        session.currentOutputLineCount = 0
        session.commandQueue.clear()
    }

    /**
     *
     * @return  true
     */
    private fun detectFullscreenMode(sessionId: String, buffer: StringBuilder, sessionManager: SessionManager): Boolean {
        // CSI ? 1049 h:
        // CSI ? 1049 l:
        val enterFullscreen = "\u001B[?1049h"
        val exitFullscreen = "\u001B[?1049l"

        val bufferContent = buffer.toString()

        val enterIndex = bufferContent.indexOf(enterFullscreen)
        val exitIndex = bufferContent.indexOf(exitFullscreen)

        if (enterIndex != -1) {
            Log.d(TAG, "Entering fullscreen mode for session $sessionId")
            
            sessionManager.updateSession(sessionId) { session ->
                session.copy(isFullscreen = true)
            }
            
            // ansiParser
            buffer.clear()
            return true
        }

        if (exitIndex != -1) {
            Log.d(TAG, "Exiting fullscreen mode for session $sessionId")
            val outputBeforeExit = bufferContent.substring(0, exitIndex)

            if (outputBeforeExit.isNotEmpty()) {
                updateCommandOutput(sessionId, outputBeforeExit, sessionManager)
            }

            sessionManager.updateSession(sessionId) { session ->
                session.copy(isFullscreen = false)
            }

            buffer.delete(0, exitIndex + exitFullscreen.length)

            finishCurrentCommand(sessionId, sessionManager)
            return true
        }
        return false
    }

    /**
     *  Canvas
     *  READY 
     */
    private fun sendWelcomeMessage(sessionId: String, sessionManager: SessionManager) {
        val session = sessionManager.getSession(sessionId) ?: return
        
        // ANSI
        // \u001B[2J -
        // \u001B[H -
        // \r\n \r \n
        val welcomeMessage = "\u001B[2J\u001B[H" +
            "   ____                \r\n" +
            "  / ___|___  _ __ _   _ \r\n" +
            " | |   / _ \\| '__| | | |\r\n" +
            " | |__| (_) | |  | |_| |\r\n" +
            "  \\____\\___/|_|   \\__, |\r\n" +
            "                  |___/ \r\n" +
            "\r\n"
        
        // ANSI Canvas
        session.ansiParser.parse(welcomeMessage)
        
        Log.d(TAG, "Screen cleared and welcome message sent to Canvas for session $sessionId")
    }

    private fun inferExitCode(output: String): Int {
        val lowered = output.lowercase()
        return if (
            "error" in lowered ||
            "failed" in lowered ||
            "exception" in lowered ||
            "not found" in lowered ||
            "traceback" in lowered
        ) {
            1
        } else {
            0
        }
    }

}
