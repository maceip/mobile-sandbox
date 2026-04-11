package com.ai.assistance.operit.terminal.ui

sealed class AgentBlock {
    data class UserPrompt(val text: String) : AgentBlock()

    data class AgentText(
        val text: String,
        val isCollapsed: Boolean = true
    ) : AgentBlock()

    data class Command(
        val command: String,
        val output: String,
        val exitCode: Int?,
        val durationMs: Long?,
        val isExpanded: Boolean = false
    ) : AgentBlock()

    data class Diff(
        val filePath: String,
        val hunks: List<DiffHunk>
    ) : AgentBlock()
}

data class DiffHunk(
    val header: String,
    val lines: List<String>
)
