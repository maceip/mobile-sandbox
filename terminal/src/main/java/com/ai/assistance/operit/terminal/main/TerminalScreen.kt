package com.ai.assistance.operit.terminal.main

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.terminal.TerminalEnv
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.view.canvas.CanvasTerminalScreen
import com.ai.assistance.operit.terminal.view.canvas.TerminalTabRenderItem

/**
 * Full-screen terminal using the Canvas view's built-in tab bar,
 * input handling, and rendering. No Compose UI chrome — the Canvas
 * view handles tabs, text input, scrollback, and keyboard.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TerminalScreen(
    env: TerminalEnv
) {
    val context = LocalContext.current
    val hostActivity = remember(context) { context.findActivity() }
    val manifestSoftInputMode = remember(hostActivity) { hostActivity?.manifestSoftInputMode() }
    val manager = remember { TerminalManager.getInstance(context) }

    // Prevent soft keyboard from resizing the terminal view.
    DisposableEffect(hostActivity, manifestSoftInputMode) {
        hostActivity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        onDispose {
            val window = hostActivity?.window
            if (window != null && manifestSoftInputMode != null) {
                window.setSoftInputMode(manifestSoftInputMode)
            }
        }
    }

    // Auto-create the first session if none exist.
    LaunchedEffect(manager) {
        if (manager.sessions.value.isEmpty()) {
            manager.createNewSession()
        }
    }

    // Map sessions to the Canvas view's tab render items.
    val tabs = env.sessions.mapIndexed { index, session ->
        TerminalTabRenderItem(
            id = session.id,
            title = session.title.ifBlank { "Shell ${index + 1}" }
        )
    }

    // Get the current session's PTY
    val currentPty = remember(env.currentSessionId, env.sessions) {
        env.sessions.find { it.id == env.currentSessionId }?.pty
    }

    CanvasTerminalScreen(
        emulator = env.terminalEmulator,
        modifier = Modifier.fillMaxSize(),
        pty = currentPty,
        onInput = { env.onRawInput(it) },
        sessionId = env.currentSessionId,
        onScrollOffsetChanged = { id, offset -> env.saveScrollOffset(id, offset) },
        getScrollOffset = { id -> env.getScrollOffset(id) },
        tabs = tabs,
        currentTabId = env.currentSessionId,
        onTabClick = { id -> env.onSwitchSession(id) },
        onTabClose = { id -> env.onCloseSession(id) },
        onNewTab = { env.onNewSession() }
    )
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun Activity.manifestSoftInputMode(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getActivityInfo(
            componentName,
            PackageManager.ComponentInfoFlags.of(0)
        ).softInputMode
    } else {
        @Suppress("DEPRECATION")
        packageManager.getActivityInfo(componentName, 0).softInputMode
    }
