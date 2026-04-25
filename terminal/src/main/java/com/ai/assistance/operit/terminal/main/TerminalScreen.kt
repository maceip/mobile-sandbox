package com.ai.assistance.operit.terminal.main

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.ai.assistance.operit.terminal.TerminalEnv
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.view.canvas.CanvasTerminalScreen
import com.ai.assistance.operit.terminal.view.canvas.ExtraKeysBar
import com.ai.assistance.operit.terminal.view.canvas.TerminalTabRenderItem

/**
 * Full-screen terminal using the Canvas view's built-in tab bar,
 * input handling, and rendering.
 *
 * Includes an extra-keys convenience bar (ESC, CTRL, TAB, arrows, PASTE)
 * that slides up when the soft keyboard is visible — inspired by the
 * v9 (maceip/v9) mobile D-pad + keyboard rail.
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

    DisposableEffect(hostActivity, manifestSoftInputMode) {
        hostActivity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        onDispose {
            val window = hostActivity?.window
            if (window != null && manifestSoftInputMode != null) {
                window.setSoftInputMode(manifestSoftInputMode)
            }
        }
    }

    LaunchedEffect(manager) {
        var attempt = 0
        while (manager.sessions.value.isEmpty() && attempt < 25) {
            attempt++
            try {
                manager.createNewSession()
            } catch (e: Exception) {
                Log.e("TerminalScreen", "createNewSession failed (attempt $attempt)", e)
            }
            delay(400)
        }
    }

    val tabs = env.sessions.mapIndexed { index, session ->
        TerminalTabRenderItem(
            id = session.id,
            title = session.title.ifBlank { "Shell ${index + 1}" },
            canClose = index > 0
        )
    }

    val currentPty = remember(env.currentSessionId, env.sessions) {
        env.sessions.find { it.id == env.currentSessionId }?.pty
    }

    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottom > 0

    Box(modifier = Modifier.fillMaxSize()) {
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

        ExtraKeysBar(
            visible = imeVisible,
            onInput = { env.onRawInput(it) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = with(density) { imeBottom.toDp() })
        )
    }
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
