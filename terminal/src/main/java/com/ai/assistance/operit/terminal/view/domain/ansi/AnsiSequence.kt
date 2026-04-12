package com.ai.assistance.operit.terminal.view.domain.ansi

import android.graphics.Color

/**
 * ANSI 
 */
sealed class AnsiSequence {
    data class Text(val char: Char) : AnsiSequence()
    
    data class ControlChar(val type: ControlCharType, val char: Char) : AnsiSequence()
    
    /** CSI (Control Sequence Introducer) - ESC[ */
    data class CSI(
        val params: List<Int>,
        val command: Char,
        val intermediates: String = "",
        val private: Boolean = false
    ) : AnsiSequence()
    
    /** OSC (Operating System Command) - ESC] */
    data class OSC(val command: Int, val data: String) : AnsiSequence()
    
    /** - ESC + */
    data class SingleEscape(val char: Char) : AnsiSequence()
    
    /** DCS (Device Control String) - ESC P */
    data class DCS(val data: String) : AnsiSequence()
    
    data class Unknown(val raw: String) : AnsiSequence()
}

enum class ControlCharType {
    NULL,           // \0 - Null
    BELL,           // \a - Bell/Alert
    BACKSPACE,      // \b - Backspace
    TAB,            // \t - Horizontal Tab
    LINE_FEED,      // \n - Line Feed/New Line
    VERTICAL_TAB,   // \v - Vertical Tab
    FORM_FEED,      // \f - Form Feed
    CARRIAGE_RETURN,// \r - Carriage Return
    ESCAPE,         // \x1B - Escape (handled separately)
    DELETE,         // \x7F - Delete
    OTHER           // Other control characters
}

/**
 * SGR (Select Graphic Rendition) 
 */
sealed class SgrParameter {
    object Reset : SgrParameter()
    object Bold : SgrParameter()
    object Dim : SgrParameter()
    object Italic : SgrParameter()
    object Underline : SgrParameter()
    object Blink : SgrParameter()
    object Inverse : SgrParameter()
    object Hidden : SgrParameter()
    object Strikethrough : SgrParameter()
    
    object NormalIntensity : SgrParameter()
    object NotItalic : SgrParameter()
    object NotUnderline : SgrParameter()
    object NotBlink : SgrParameter()
    object NotInverse : SgrParameter()
    object NotHidden : SgrParameter()
    object NotStrikethrough : SgrParameter()
    
    data class ForegroundColor(val color: Int) : SgrParameter()
    data class BackgroundColor(val color: Int) : SgrParameter()
    data class ForegroundColor256(val index: Int) : SgrParameter()
    data class BackgroundColor256(val index: Int) : SgrParameter()
    data class ForegroundColorRgb(val r: Int, val g: Int, val b: Int) : SgrParameter()
    data class BackgroundColorRgb(val r: Int, val g: Int, val b: Int) : SgrParameter()
    
    object DefaultForeground : SgrParameter()
    object DefaultBackground : SgrParameter()
}

enum class CursorDirection {
    UP,
    DOWN,
    FORWARD,
    BACK
}

enum class EraseMode {
    TO_END,
    TO_START,
    ALL,
    SCROLLBACK      // J=3
}

data class TerminalMode(
    val number: Int,
    val isPrivate: Boolean,
    val name: String
) {
    companion object {
        // DEC
        val CURSOR_KEYS = TerminalMode(1, true, "DECCKM")          // Cursor Keys Mode
        val ANSI_VT52 = TerminalMode(2, true, "DECANM")            // ANSI/VT52 Mode
        val COLUMN_MODE = TerminalMode(3, true, "DECCOLM")         // Column Mode
        val SMOOTH_SCROLL = TerminalMode(4, true, "DECSCLM")       // Smooth Scroll
        val REVERSE_VIDEO = TerminalMode(5, true, "DECSCNM")       // Reverse Video
        val ORIGIN_MODE = TerminalMode(6, true, "DECOM")           // Origin Mode
        val AUTO_WRAP = TerminalMode(7, true, "DECAWM")            // Auto Wrap Mode
        val AUTO_REPEAT = TerminalMode(8, true, "DECARM")          // Auto Repeat Mode
        val CURSOR_VISIBLE = TerminalMode(25, true, "DECTCEM")     // Text Cursor Enable
        val ALT_SCREEN = TerminalMode(1049, true, "ALT_SCREEN")    // Alternate Screen Buffer
        val BRACKETED_PASTE = TerminalMode(2004, true, "BRACKETED_PASTE")
        
        // ANSI
        val INSERT_MODE = TerminalMode(4, false, "IRM")            // Insert/Replace Mode
        val LINE_FEED_MODE = TerminalMode(20, false, "LNM")        // Line Feed/New Line Mode
    }
}

data class TextAttributes(
    val fgColor: Int = Color.WHITE,
    val bgColor: Int = Color.BLACK,
    val isBold: Boolean = false,
    val isDim: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isBlinking: Boolean = false,
    val isInverse: Boolean = false,
    val isHidden: Boolean = false,
    val isStrikethrough: Boolean = false
) {
    fun reset() = TextAttributes()
    
    fun applyBold(value: Boolean) = copy(isBold = value, isDim = if (value) false else isDim)
    fun applyDim(value: Boolean) = copy(isDim = value, isBold = if (value) false else isBold)
    fun applyItalic(value: Boolean) = copy(isItalic = value)
    fun applyUnderline(value: Boolean) = copy(isUnderline = value)
    fun applyBlinking(value: Boolean) = copy(isBlinking = value)
    fun applyInverse(value: Boolean) = copy(isInverse = value)
    fun applyHidden(value: Boolean) = copy(isHidden = value)
    fun applyStrikethrough(value: Boolean) = copy(isStrikethrough = value)
    fun applyForeground(color: Int) = copy(fgColor = color)
    fun applyBackground(color: Int) = copy(bgColor = color)
    
        fun getEffectiveForeground(): Int = if (isInverse) bgColor else fgColor
    
        fun getEffectiveBackground(): Int = if (isInverse) fgColor else bgColor
} 