package com.ai.assistance.operit.terminal.view.canvas

import android.graphics.PixelFormat
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator

/**
 * Primary Compose wrapper for the Canvas terminal.
 * Passes tab state to the Canvas view's built-in tab bar.
 */
@Composable
fun CanvasTerminalScreen(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    config: RenderConfig = RenderConfig(),
    pty: com.ai.assistance.operit.terminal.Pty? = null,
    imeAnimationOffsetPx: Int = 0,
    committedImeBottomInsetPx: Int = 0,
    onInput: (String) -> Unit = {},
    onScaleChanged: (Float) -> Unit = {},
    sessionId: String? = null,
    onScrollOffsetChanged: ((String, Float) -> Unit)? = null,
    getScrollOffset: ((String) -> Float)? = null,
    tabs: List<TerminalTabRenderItem> = emptyList(),
    currentTabId: String? = null,
    onTabClick: ((String) -> Unit)? = null,
    onTabClose: ((String) -> Unit)? = null,
    onNewTab: (() -> Unit)? = null
) {
    AndroidView(
        factory = { context ->
            CanvasTerminalView(context).apply {
                // Force MATCH_PARENT layout params on the underlying
                // SurfaceView. Compose's AndroidView is supposed to
                // size the View from the modifier constraints, but
                // SurfaceView's measure pass races with surface
                // creation — without explicit LayoutParams, the View
                // can land at 0×0 on first layout and only resolve
                // its real size after a configuration change. The
                // user reported "black screen until I unfolded the
                // device" which is exactly this race.
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Tell the SurfaceView its surface should be opaque,
                // not translucent. The default TRANSLUCENT format
                // makes the surface alpha-blend with whatever's
                // behind it, which on a freshly-created surface is
                // black — combined with the layout race above, we
                // get the dark void the user described.
                holder.setFormat(PixelFormat.OPAQUE)

                setConfig(config)
                setEmulator(emulator)
                setPty(pty)
                setImeViewportState(
                    animationOffsetPx = imeAnimationOffsetPx,
                    committedBottomInsetPx = committedImeBottomInsetPx
                )
                setInputCallback(onInput)
                setScaleCallback(onScaleChanged)
                setSessionScrollCallbacks(sessionId, onScrollOffsetChanged, getScrollOffset)
                setTabBarState(tabs, currentTabId, onTabClick, onTabClose, onNewTab)
                post { requestFocus() }
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
            }
        },
        update = { view ->
            view.setConfig(config)
            view.setEmulator(emulator)
            view.setPty(pty)
            view.setImeViewportState(
                animationOffsetPx = imeAnimationOffsetPx,
                committedBottomInsetPx = committedImeBottomInsetPx
            )
            view.setInputCallback(onInput)
            view.setSessionScrollCallbacks(sessionId, onScrollOffsetChanged, getScrollOffset)
            view.setTabBarState(tabs, currentTabId, onTabClick, onTabClose, onNewTab)
        },
        onRelease = { view ->
            view.release()
        },
        modifier = modifier
    )
}
