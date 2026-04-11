package com.ai.assistance.operit.terminal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ai.assistance.operit.terminal.CommandExecutionEvent
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.UserInputEvent
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.LaunchedEffect

@Stable
class AgentUiState {
    private val sessions = mutableStateMapOf<String, AgentSessionBuffer>()

    fun blocks(sessionId: String?): SnapshotStateList<AgentBlock> {
        if (sessionId == null) return mutableStateListOf()
        return sessions.getOrPut(sessionId) { AgentSessionBuffer() }.blocks
    }

    fun onUserInput(event: UserInputEvent) {
        val session = sessions.getOrPut(event.sessionId) { AgentSessionBuffer() }
        if (event.isCommand && event.text.isNotBlank()) {
            session.flushText()
            session.blocks += AgentBlock.UserPrompt(event.text)
        }
    }

    fun onOutput(sessionId: String, chunk: String) {
        val session = sessions.getOrPut(sessionId) { AgentSessionBuffer() }
        session.appendChunk(chunk)
    }

    fun onCommandEvent(event: CommandExecutionEvent) {
        val session = sessions.getOrPut(event.sessionId) { AgentSessionBuffer() }
        session.onCommandEvent(event)
    }

    fun toggleText(sessionId: String, index: Int) {
        val blocks = sessions[sessionId]?.blocks ?: return
        val block = blocks.getOrNull(index) as? AgentBlock.AgentText ?: return
        blocks[index] = block.copy(isCollapsed = !block.isCollapsed)
    }

    fun toggleCommand(sessionId: String, index: Int) {
        val blocks = sessions[sessionId]?.blocks ?: return
        val block = blocks.getOrNull(index) as? AgentBlock.Command ?: return
        blocks[index] = block.copy(isExpanded = !block.isExpanded)
    }
}

private class AgentSessionBuffer {
    val blocks = mutableStateListOf<AgentBlock>()
    private val lineBuffer = StringBuilder()
    private val runningCommands = linkedMapOf<String, Int>()

    fun appendChunk(chunk: String) {
        lineBuffer.append(chunk)
        var newlineIndex = lineBuffer.indexOf("\n")
        while (newlineIndex >= 0) {
            val line = lineBuffer.substring(0, newlineIndex).trimEnd('\r')
            lineBuffer.delete(0, newlineIndex + 1)
            acceptLine(line)
            newlineIndex = lineBuffer.indexOf("\n")
        }
        val trailing = lineBuffer.toString()
        if (trailing.isNotBlank() && runningCommands.isEmpty()) {
            upsertAgentText(trailing)
            lineBuffer.clear()
        }
    }

    fun onCommandEvent(event: CommandExecutionEvent) {
        flushText()
        val existingIndex = runningCommands[event.commandId]
        val commandOutput = event.outputChunk.trimEnd()
        val expanded = event.exitCode != null && event.exitCode != 0
        if (existingIndex == null) {
            val block = AgentBlock.Command(
                command = event.command,
                output = commandOutput,
                exitCode = if (event.isCompleted) event.exitCode else null,
                durationMs = event.durationMs,
                isExpanded = expanded
            )
            blocks += block
            runningCommands[event.commandId] = blocks.lastIndex
        } else {
            val existing = blocks[existingIndex] as? AgentBlock.Command ?: return
            blocks[existingIndex] = existing.copy(
                output = if (commandOutput.isBlank()) existing.output else commandOutput,
                exitCode = if (event.isCompleted) event.exitCode else existing.exitCode,
                durationMs = event.durationMs ?: existing.durationMs,
                isExpanded = existing.isExpanded || expanded
            )
        }
        if (event.isCompleted) {
            runningCommands.remove(event.commandId)
        }
    }

    fun flushText() {
        val trailing = lineBuffer.toString().trim()
        if (trailing.isNotEmpty()) {
            upsertAgentText(trailing)
        }
        lineBuffer.clear()
    }

    private fun acceptLine(line: String) {
        if (line.isBlank()) {
            flushText()
            return
        }
        when {
            isDiffLine(line) -> addDiffLine(line)
            isCommandMarker(line) -> {
                flushText()
                blocks += AgentBlock.Command(
                    command = stripCommandMarker(line),
                    output = "",
                    exitCode = null,
                    durationMs = null,
                    isExpanded = false
                )
            }
            else -> upsertAgentText(line)
        }
    }

    private fun upsertAgentText(line: String) {
        val last = blocks.lastOrNull()
        if (last is AgentBlock.AgentText) {
            blocks[blocks.lastIndex] = last.copy(text = last.text + "\n" + line)
        } else {
            blocks += AgentBlock.AgentText(text = line)
        }
    }

    private fun addDiffLine(line: String) {
        val last = blocks.lastOrNull()
        if (last is AgentBlock.Diff) {
            val hunks = last.hunks.toMutableList()
            val current = hunks.lastOrNull()
            if (current == null || line.startsWith("@@")) {
                hunks += DiffHunk(header = line, lines = emptyList())
            } else {
                hunks[hunks.lastIndex] = current.copy(lines = current.lines + line)
            }
            blocks[blocks.lastIndex] = last.copy(hunks = hunks)
        } else {
            blocks += AgentBlock.Diff(
                filePath = "Edited file",
                hunks = listOf(DiffHunk(header = line, lines = emptyList()))
            )
        }
    }

    private fun isCommandMarker(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("$ ") ||
            trimmed.startsWith("❯ ") ||
            trimmed.startsWith("● ") ||
            trimmed.startsWith("bash ") ||
            trimmed.startsWith("git ") ||
            trimmed.startsWith("npm ") ||
            trimmed.startsWith("pnpm ") ||
            trimmed.startsWith("cargo ") ||
            trimmed.startsWith("gradle ")
    }

    private fun stripCommandMarker(line: String): String {
        return line.trimStart().removePrefix("$ ").removePrefix("❯ ").removePrefix("● ")
    }

    private fun isDiffLine(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("@@") ||
            trimmed.startsWith("--- ") ||
            trimmed.startsWith("+++ ") ||
            trimmed.startsWith("+") ||
            trimmed.startsWith("-")
    }
}

@Composable
fun rememberAgentUiState(terminalManager: TerminalManager): AgentUiState {
    val state = remember { AgentUiState() }

    LaunchedEffect(terminalManager) {
        terminalManager.userInputEvents.collectLatest { state.onUserInput(it) }
    }
    LaunchedEffect(terminalManager) {
        terminalManager.sessionOutputEvents.collectLatest { state.onOutput(it.sessionId, it.chunk) }
    }
    LaunchedEffect(terminalManager) {
        terminalManager.commandExecutionEvents.collectLatest { state.onCommandEvent(it) }
    }

    return state
}
