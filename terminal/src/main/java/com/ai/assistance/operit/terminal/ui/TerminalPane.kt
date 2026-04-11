package com.ai.assistance.operit.terminal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.ai.assistance.operit.terminal.TerminalEnv
import com.ai.assistance.operit.terminal.view.canvas.CanvasTerminalScreen
import com.ai.assistance.operit.terminal.view.canvas.RenderConfig

@Composable
fun TerminalPane(
    env: TerminalEnv,
    modifier: Modifier = Modifier
) {
    val currentPty = remember(env.currentSessionId, env.sessions) {
        env.sessions.find { it.id == env.currentSessionId }?.pty
    }
    CanvasTerminalScreen(
        emulator = env.terminalEmulator,
        modifier = modifier,
        config = RenderConfig(),
        pty = currentPty,
        onInput = { env.onRawInput(it) },
        sessionId = env.currentSessionId,
        onScrollOffsetChanged = { id, offset -> env.saveScrollOffset(id, offset) },
        getScrollOffset = { id -> env.getScrollOffset(id) }
    )
}
