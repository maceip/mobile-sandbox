package com.ai.assistance.operit.terminal.ui.blocks

import androidx.compose.runtime.Composable

@Composable
fun ErrorBlock(
    command: String,
    output: String,
    durationMs: Long?
) {
    CommandBlock(
        command = command,
        output = output,
        exitCode = 1,
        durationMs = durationMs,
        isExpanded = true,
        onToggle = {}
    )
}
