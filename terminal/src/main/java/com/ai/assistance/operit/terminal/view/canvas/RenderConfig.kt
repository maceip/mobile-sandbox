package com.ai.assistance.operit.terminal.view.canvas

import android.graphics.Color
import android.graphics.Typeface

data class RenderConfig(
    val fontSize: Float = 42f,
    val typeface: Typeface = Typeface.MONOSPACE,
    val nerdFontPath: String? = null, // Nerd Font
    val backgroundColor: Int = Color.BLACK,
    val foregroundColor: Int = Color.WHITE,
    val defaultForegroundColor: Int = foregroundColor,
    val cursorColor: Int = Color.GREEN,
    val cursorBlinkRate: Long = 500L,
    val lineSpacing: Float = 0.1f,
    val charSpacing: Float = 0f,
    val targetFps: Int = 60,
    val enableCharCache: Boolean = true,
    val enableDirtyTracking: Boolean = true,
    val enableFrameRateAdaptation: Boolean = true,
    val paddingLeft: Float = 16f,
    val paddingTop: Float = 16f,
    val paddingRight: Float = 16f,
    val paddingBottom: Float = 16f
) {
    fun withFontSize(newSize: Float): RenderConfig {
        return copy(fontSize = newSize)
    }

    fun withTypeface(newTypeface: Typeface): RenderConfig {
        return copy(typeface = newTypeface)
    }

    fun withNerdFont(nerdFontPath: String?): RenderConfig {
        return copy(nerdFontPath = nerdFontPath)
    }

    fun getFrameDelay(): Long {
        return 1000L / targetFps.coerceAtLeast(1)
    }
}

