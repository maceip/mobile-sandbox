package com.ai.assistance.operit.terminal.view.domain.ansi

import android.graphics.Color
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

data class TerminalChar(
    val char: Char = ' ',
    val attributes: TextAttributes = TextAttributes()
) {
    constructor(
        char: Char,
        fgColor: Int,
        bgColor: Int,
        isBold: Boolean = false,
        isDim: Boolean = false,
        isItalic: Boolean = false,
        isUnderline: Boolean = false,
        isBlinking: Boolean = false,
        isInverse: Boolean = false,
        isHidden: Boolean = false,
        isStrikethrough: Boolean = false
    ) : this(
        char,
        TextAttributes(
            fgColor, bgColor, isBold, isDim, isItalic,
            isUnderline, isBlinking, isInverse, isHidden, isStrikethrough
        )
    )
    
    // API
    val fgColor: Int get() = attributes.fgColor
    val bgColor: Int get() = attributes.bgColor
    val isBold: Boolean get() = attributes.isBold
    val isDim: Boolean get() = attributes.isDim
    val isItalic: Boolean get() = attributes.isItalic
    val isUnderline: Boolean get() = attributes.isUnderline
    val isBlinking: Boolean get() = attributes.isBlinking
    val isInverse: Boolean get() = attributes.isInverse
    val isHidden: Boolean get() = attributes.isHidden
    val isStrikethrough: Boolean get() = attributes.isStrikethrough
}

/**
 * ANSI 
 *  VT100/xterm 
 */
class AnsiTerminalEmulator(
    private var screenWidth: Int = 40,
    private var screenHeight: Int = 60,
    private val historySize: Int = 200
) {
    companion object {
        private const val TAG = "AnsiTerminalEmulator"
        private const val TAB_SIZE = 8
    }
    
    private var screenBuffer: Array<Array<TerminalChar>> =
        Array(screenHeight) { Array(screenWidth) { TerminalChar() } }
    
    // \n
    private var lineWrapped: BooleanArray = BooleanArray(screenHeight) { false }
    
    private val historyBuffer: MutableList<Array<TerminalChar>> = mutableListOf()
    private val historyWrapped: MutableList<Boolean> = mutableListOf()
    
    // vim
    private var altScreenBuffer: Array<Array<TerminalChar>>? = null
    private var isAltScreenActive = false
    
    private var cursorX: Int = 0
    private var cursorY: Int = 0
    
    private var currentAttributes = TextAttributes()
    
    // DECSC/DECRC
    private var savedCursorX: Int = 0
    private var savedCursorY: Int = 0
    private var savedAttributes = TextAttributes()
    
    private val terminalModes = mutableMapOf<Int, Boolean>()
    private var cursorVisible = true
    private var autoWrapMode = true
    private var originMode = false
    
    // 0-based, inclusive
    private var scrollTop = 0
    private var scrollBottom = screenHeight - 1
    
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()
    
    private val newOutputListeners = CopyOnWriteArrayList<() -> Unit>()
    
    private val fullContentView = FullContentView()
    
    /**
     *  ANSI 
     */
    fun parse(text: String) {
        val scanner = AnsiScanner(text)
        
        while (scanner.hasNext()) {
            when (val seq = scanner.scanNext()) {
                is AnsiSequence.Text -> handleText(seq.char)
                is AnsiSequence.ControlChar -> handleControlChar(seq)
                is AnsiSequence.CSI -> handleCSI(seq)
                is AnsiSequence.OSC -> handleOSC(seq)
                is AnsiSequence.SingleEscape -> handleSingleEscape(seq)
                is AnsiSequence.DCS -> handleDCS(seq)
                is AnsiSequence.Unknown -> {
                    Log.w(TAG, "Unknown sequence: ${seq.raw}")
                }
                null -> break
            }
        }
        
        notifyChange()
        notifyNewOutput()
    }
    
    private fun handleText(char: Char) {
        if (cursorX >= screenWidth) {
            if (autoWrapMode) {
                if (cursorY >= 0 && cursorY < screenHeight) {
                    lineWrapped[cursorY] = true
                }
                cursorX = 0
                cursorY++
                if (cursorY > scrollBottom) {
                    cursorY = scrollBottom
                    scrollUp(1)
                }
            } else {
                cursorX = screenWidth - 1
            }
        }
        
        if (cursorY < screenHeight && cursorX < screenWidth) {
            screenBuffer[cursorY][cursorX] = TerminalChar(char, currentAttributes)
            cursorX++
        }
    }
    
    private fun handleControlChar(seq: AnsiSequence.ControlChar) {
        when (seq.type) {
            ControlCharType.BELL -> {
                // -
                Log.d(TAG, "Bell")
            }
            ControlCharType.BACKSPACE -> {
                cursorX = (cursorX - 1).coerceAtLeast(0)
            }
            ControlCharType.TAB -> {
                val nextTabStop = ((cursorX / TAB_SIZE) + 1) * TAB_SIZE
                cursorX = nextTabStop.coerceAtMost(screenWidth - 1)
            }
            ControlCharType.LINE_FEED -> {
                if (cursorY >= 0 && cursorY < screenHeight) {
                    lineWrapped[cursorY] = false
                }
                cursorY++
                if (cursorY > scrollBottom) {
                    cursorY = scrollBottom
                    scrollUp(1)
                }
            }
            ControlCharType.VERTICAL_TAB, ControlCharType.FORM_FEED -> {
                cursorY++
                if (cursorY >= screenHeight) {
                    cursorY = screenHeight - 1
                    scrollUp(1)
                }
            }
            ControlCharType.CARRIAGE_RETURN -> {
                cursorX = 0
            }
            ControlCharType.DELETE -> {
                if (cursorY < screenHeight && cursorX < screenWidth) {
                    screenBuffer[cursorY][cursorX] = TerminalChar()
                }
            }
            else -> {
                Log.d(TAG, "Unhandled control char: ${seq.type}")
            }
        }
    }
    
    /**
     *  CSI 
     */
    private fun handleCSI(csi: AnsiSequence.CSI) {
        val params = csi.params
        val p1 = params.firstOrNull() ?: 0
        
        when (csi.command) {
            'H', 'f' -> { // CUP - Cursor Position
                val row = if (params.isNotEmpty()) (params[0] - 1).coerceAtLeast(0) else 0
                val col = if (params.size > 1) (params[1] - 1).coerceAtLeast(0) else 0
                
                if (originMode) {
                    cursorY = (scrollTop + row).coerceIn(scrollTop, scrollBottom)
                    cursorX = col.coerceIn(0, screenWidth - 1)
                } else {
                    cursorY = row.coerceIn(0, screenHeight - 1)
                    cursorX = col.coerceIn(0, screenWidth - 1)
                }
            }
            'A' -> { // CUU - Cursor Up
                val n = p1.coerceAtLeast(1)
                cursorY = (cursorY - n).coerceAtLeast(scrollTop)
            }
            'B' -> { // CUD - Cursor Down
                val n = p1.coerceAtLeast(1)
                cursorY = (cursorY + n).coerceAtMost(scrollBottom)
            }
            'C' -> { // CUF - Cursor Forward
                val n = p1.coerceAtLeast(1)
                cursorX = (cursorX + n).coerceAtMost(screenWidth - 1)
            }
            'D' -> { // CUB - Cursor Back
                val n = p1.coerceAtLeast(1)
                cursorX = (cursorX - n).coerceAtLeast(0)
            }
            'E' -> { // CNL - Cursor Next Line
                val n = p1.coerceAtLeast(1)
                cursorY = (cursorY + n).coerceAtMost(scrollBottom)
                cursorX = 0
            }
            'F' -> { // CPL - Cursor Previous Line
                val n = p1.coerceAtLeast(1)
                cursorY = (cursorY - n).coerceAtLeast(scrollTop)
                cursorX = 0
            }
            'G' -> { // CHA - Cursor Horizontal Absolute
                cursorX = (p1 - 1).coerceIn(0, screenWidth - 1)
            }
            'd' -> { // VPA - Vertical Position Absolute
                cursorY = (p1 - 1).coerceIn(0, screenHeight - 1)
            }
            
            'J' -> { // ED - Erase in Display
                when (p1) {
                    0 -> eraseFromCursorToEnd()
                    1 -> eraseFromStartToCursor()
                    2 -> clearScreen()
                    3 -> clearScreenAndScrollback()
                }
            }
            'K' -> { // EL - Erase in Line
                when (p1) {
                    0 -> eraseLineFromCursor()
                    1 -> eraseLineToCursor()
                    2 -> eraseLine()
                }
            }
            
            'S' -> scrollUp(p1.coerceAtLeast(1)) // SU - Scroll Up
            'T' -> scrollDown(p1.coerceAtLeast(1)) // SD - Scroll Down
            'r' -> { // DECSTBM - Set Scrolling Region
                val top = if (params.isNotEmpty()) (params[0] - 1).coerceIn(0, screenHeight - 1) else 0
                val bottom = if (params.size > 1) (params[1] - 1).coerceIn(0, screenHeight - 1) else screenHeight - 1
                if (top < bottom) {
                    scrollTop = top
                    scrollBottom = bottom
                    cursorX = 0
                    cursorY = if (originMode) scrollTop else 0
                }
            }
            
            // /
            'L' -> insertLines(p1.coerceAtLeast(1)) // IL - Insert Lines
            'M' -> deleteLines(p1.coerceAtLeast(1)) // DL - Delete Lines
            '@' -> insertChars(p1.coerceAtLeast(1)) // ICH - Insert Characters
            'P' -> deleteChars(p1.coerceAtLeast(1)) // DCH - Delete Characters
            'X' -> eraseChars(p1.coerceAtLeast(1)) // ECH - Erase Characters
            
            'm' -> handleSGR(params)
            
            'h' -> setMode(params, csi.private, true)
            'l' -> setMode(params, csi.private, false)
            
            // / (ANSI.SYS )
            's' -> saveCursorAndAttrs()
            'u' -> restoreCursorAndAttrs()
            
            else -> {
                Log.w(TAG, "Unsupported CSI command: ${csi.command} with params: $params")
            }
        }
    }
    
    /**
     *  SGR (Select Graphic Rendition) - 
     */
    private fun handleSGR(params: List<Int>) {
        if (params.isEmpty()) {
            currentAttributes = TextAttributes()
            return
        }
        
        var i = 0
        while (i < params.size) {
            when (val p = params[i]) {
                0 -> currentAttributes = TextAttributes()
                1 -> currentAttributes = currentAttributes.applyBold(true)
                2 -> currentAttributes = currentAttributes.applyDim(true)
                3 -> currentAttributes = currentAttributes.applyItalic(true)
                4 -> currentAttributes = currentAttributes.applyUnderline(true)
                5, 6 -> currentAttributes = currentAttributes.applyBlinking(true)
                7 -> currentAttributes = currentAttributes.applyInverse(true)
                8 -> currentAttributes = currentAttributes.applyHidden(true)
                9 -> currentAttributes = currentAttributes.applyStrikethrough(true)
                
                21, 22 -> currentAttributes = currentAttributes.applyBold(false).applyDim(false)
                23 -> currentAttributes = currentAttributes.applyItalic(false)
                24 -> currentAttributes = currentAttributes.applyUnderline(false)
                25 -> currentAttributes = currentAttributes.applyBlinking(false)
                27 -> currentAttributes = currentAttributes.applyInverse(false)
                28 -> currentAttributes = currentAttributes.applyHidden(false)
                29 -> currentAttributes = currentAttributes.applyStrikethrough(false)
                
                // ()
                in 30..37 -> currentAttributes = currentAttributes.applyForeground(
                    AnsiColorUtils.getAnsiColor(p - 30)
                )
                38 -> {
                    val result = AnsiColorUtils.parseColorFromSgr(params, i + 1)
                    if (result != null) {
                        currentAttributes = currentAttributes.applyForeground(result.first)
                        i += result.second
                    }
                }
                39 -> currentAttributes = currentAttributes.applyForeground(Color.WHITE)
                
                // ()
                in 40..47 -> currentAttributes = currentAttributes.applyBackground(
                    AnsiColorUtils.getAnsiColor(p - 40)
                )
                48 -> {
                    val result = AnsiColorUtils.parseColorFromSgr(params, i + 1)
                    if (result != null) {
                        currentAttributes = currentAttributes.applyBackground(result.first)
                        i += result.second
                    }
                }
                49 -> currentAttributes = currentAttributes.applyBackground(Color.BLACK)
                
                // ()
                in 90..97 -> currentAttributes = currentAttributes.applyForeground(
                    AnsiColorUtils.getAnsiBrightColor(p - 90)
                )
                
                // ()
                in 100..107 -> currentAttributes = currentAttributes.applyBackground(
                    AnsiColorUtils.getAnsiBrightColor(p - 100)
                )
                
                else -> Log.w(TAG, "Unsupported SGR parameter: $p")
            }
            i++
        }
    }
    
    private fun setMode(params: List<Int>, isPrivate: Boolean, enable: Boolean) {
        for (param in params) {
            terminalModes[param] = enable
            
            if (isPrivate) {
                when (param) {
                    1 -> {} // DECCKM -
                    3 -> {} // DECCOLM - 132
                    6 -> originMode = enable // DECOM -
                    7 -> autoWrapMode = enable // DECAWM -
                    25 -> cursorVisible = enable // DECTCEM -
                    1049 -> toggleAltScreen(enable)
                    2004 -> {} // Bracketed paste mode
                }
            }
        }
    }
    
    private fun toggleAltScreen(enable: Boolean) {
        if (enable && !isAltScreenActive) {
            altScreenBuffer = screenBuffer
            screenBuffer = Array(screenHeight) { Array(screenWidth) { TerminalChar() } }
            isAltScreenActive = true
            cursorX = 0
            cursorY = 0
        } else if (!enable && isAltScreenActive) {
            altScreenBuffer?.let {
                screenBuffer = it
                altScreenBuffer = null
            }
            isAltScreenActive = false
        }
    }
    
    /**
     *  OSC (Operating System Command)
     */
    private fun handleOSC(osc: AnsiSequence.OSC) {
        when (osc.command) {
            0, 1, 2 -> {
                Log.d(TAG, "Set window title: ${osc.data}")
            }
            4 -> {
                Log.d(TAG, "Set color palette: ${osc.data}")
            }
            else -> {
                Log.d(TAG, "Unsupported OSC command: ${osc.command}")
            }
        }
    }
    
    private fun handleSingleEscape(seq: AnsiSequence.SingleEscape) {
        when (seq.char) {
            '7' -> saveCursorAndAttrs() // DECSC
            '8' -> restoreCursorAndAttrs() // DECRC
            'c' -> resetTerminal() // RIS
            'D' -> { // IND - Index (move down, scroll if needed)
                cursorY++
                if (cursorY > scrollBottom) {
                    cursorY = scrollBottom
                    scrollUp(1)
                }
            }
            'E' -> { // NEL - Next Line
                cursorX = 0
                cursorY++
                if (cursorY > scrollBottom) {
                    cursorY = scrollBottom
                    scrollUp(1)
                }
            }
            'H' -> {} // HTS - Set Tab Stop
            'M' -> { // RI - Reverse Index (move up, scroll if needed)
                cursorY--
                if (cursorY < scrollTop) {
                    cursorY = scrollTop
                    scrollDown(1)
                }
            }
            'Z' -> {} // DECID - Identify Terminal
            else -> Log.w(TAG, "Unsupported single escape: ${seq.char}")
        }
    }
    
    /**
     *  DCS (Device Control String)
     */
    private fun handleDCS(dcs: AnsiSequence.DCS) {
        Log.d(TAG, "DCS sequence (not implemented): ${dcs.data}")
    }
    
    // === ===
    
    private fun scrollUp(lines: Int = 1) {
        for (i in 0 until lines) {
            if (!isAltScreenActive && scrollTop == 0) {
                val topLine = screenBuffer[scrollTop].copyOf()
                val topWrapped = lineWrapped[scrollTop]
                historyBuffer.add(topLine)
                historyWrapped.add(topWrapped)
                
                if (historyBuffer.size > historySize) {
                    historyBuffer.removeAt(0)
                    historyWrapped.removeAt(0)
                }
            }
            
            for (y in scrollTop until scrollBottom) {
                screenBuffer[y] = screenBuffer[y + 1]
                lineWrapped[y] = lineWrapped[y + 1]
            }
            screenBuffer[scrollBottom] = Array(screenWidth) { 
                TerminalChar(attributes = currentAttributes.copy(
                    fgColor = Color.WHITE,
                    bgColor = Color.BLACK,
                    isBold = false,
                    isDim = false,
                    isItalic = false,
                    isUnderline = false,
                    isBlinking = false,
                    isInverse = false,
                    isHidden = false,
                    isStrikethrough = false
                ))
            }
            lineWrapped[scrollBottom] = false
        }
    }
    
    private fun scrollDown(lines: Int = 1) {
        for (i in 0 until lines) {
            for (y in scrollBottom downTo scrollTop + 1) {
                screenBuffer[y] = screenBuffer[y - 1]
            }
            screenBuffer[scrollTop] = Array(screenWidth) { TerminalChar() }
        }
    }
    
    private fun clearScreen() {
        for (y in 0 until screenHeight) {
            for (x in 0 until screenWidth) {
                screenBuffer[y][x] = TerminalChar()
            }
        }
        cursorX = 0
        cursorY = 0
    }
    
    private fun clearScreenAndScrollback() {
        clearScreen()
        historyBuffer.clear()
        historyWrapped.clear()
    }
    
    private fun eraseFromCursorToEnd() {
        for (x in cursorX until screenWidth) {
            screenBuffer[cursorY][x] = TerminalChar()
        }
        for (y in cursorY + 1 until screenHeight) {
            for (x in 0 until screenWidth) {
                screenBuffer[y][x] = TerminalChar()
            }
        }
    }
    
    private fun eraseFromStartToCursor() {
        for (y in 0 until cursorY) {
            for (x in 0 until screenWidth) {
                screenBuffer[y][x] = TerminalChar()
            }
        }
        for (x in 0..cursorX) {
            screenBuffer[cursorY][x] = TerminalChar()
        }
    }
    
    private fun eraseLineFromCursor() {
        for (x in cursorX until screenWidth) {
            screenBuffer[cursorY][x] = TerminalChar()
        }
    }
    
    private fun eraseLineToCursor() {
        for (x in 0..cursorX) {
            screenBuffer[cursorY][x] = TerminalChar()
        }
    }
    
    private fun eraseLine() {
        for (x in 0 until screenWidth) {
            screenBuffer[cursorY][x] = TerminalChar()
        }
    }
    
    private fun insertLines(n: Int) {
        for (i in 0 until n) {
            for (y in scrollBottom downTo cursorY + 1) {
                screenBuffer[y] = screenBuffer[y - 1]
            }
            screenBuffer[cursorY] = Array(screenWidth) { TerminalChar() }
        }
    }
    
    private fun deleteLines(n: Int) {
        for (i in 0 until n) {
            for (y in cursorY until scrollBottom) {
                screenBuffer[y] = screenBuffer[y + 1]
            }
            screenBuffer[scrollBottom] = Array(screenWidth) { TerminalChar() }
        }
    }
    
    private fun insertChars(n: Int) {
        val line = screenBuffer[cursorY]
        for (i in 0 until n.coerceAtMost(screenWidth - cursorX)) {
            for (x in screenWidth - 1 downTo cursorX + 1) {
                line[x] = line[x - 1]
            }
            line[cursorX] = TerminalChar()
        }
    }
    
    private fun deleteChars(n: Int) {
        val line = screenBuffer[cursorY]
        for (i in 0 until n.coerceAtMost(screenWidth - cursorX)) {
            for (x in cursorX until screenWidth - 1) {
                line[x] = line[x + 1]
            }
            line[screenWidth - 1] = TerminalChar()
        }
    }
    
    private fun eraseChars(n: Int) {
        for (x in cursorX until (cursorX + n).coerceAtMost(screenWidth)) {
            screenBuffer[cursorY][x] = TerminalChar()
        }
    }
    
    private fun saveCursorAndAttrs() {
        savedCursorX = cursorX
        savedCursorY = cursorY
        savedAttributes = currentAttributes
    }
    
    private fun restoreCursorAndAttrs() {
        cursorX = savedCursorX
        cursorY = savedCursorY
        currentAttributes = savedAttributes
    }
    
    private fun resetTerminal() {
        clearScreen()
        currentAttributes = TextAttributes()
        cursorX = 0
        cursorY = 0
        scrollTop = 0
        scrollBottom = screenHeight - 1
        terminalModes.clear()
        autoWrapMode = true
        originMode = false
        cursorVisible = true
    }
    
    // === API ===
    
    fun renderScreenToString(): String {
        val builder = StringBuilder()
        for (y in 0 until screenHeight) {
            for (x in 0 until screenWidth) {
                builder.append(screenBuffer[y][x].char)
            }
            if (y < screenHeight - 1) {
                builder.append('\n')
            }
        }
        return builder.toString()
    }
    
    fun getCursorX(): Int = cursorX
    fun getCursorY(): Int = cursorY
    fun isCursorVisible(): Boolean = cursorVisible
    
    fun getScreenContent(): Array<Array<TerminalChar>> = screenBuffer
    
    /**
     *  + 
     */
    fun getFullContent(): List<Array<TerminalChar>> {
        return fullContentView
    }
    
    /**
     * +
     */
    private inner class FullContentView : AbstractList<Array<TerminalChar>>() {
        override val size: Int
            get() = historyBuffer.size + screenBuffer.size
        
        override fun get(index: Int): Array<TerminalChar> {
            return if (index < historyBuffer.size) {
                historyBuffer[index]
            } else {
                screenBuffer[index - historyBuffer.size]
            }
        }
    }
    
    fun getHistorySize(): Int = historyBuffer.size
    
    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth == screenWidth && newHeight == screenHeight) return
        
        // -> -> ->
        val logicalLines = collectLogicalLines()
        trimTrailingEmptyLines(logicalLines)
        val (newPhysicalLines, newPhysicalWrapped) = rewrapLogicalLines(logicalLines, newWidth)
        
        screenWidth = newWidth
        screenHeight = newHeight
        distributeToBuffers(newPhysicalLines, newPhysicalWrapped)
        
        scrollTop = 0
        scrollBottom = screenHeight - 1
        cursorX = 0
        
        notifyChange()
    }
    
    /**
     * +
     */
    private fun collectLogicalLines(): MutableList<MutableList<TerminalChar>> {
        val logicalLines = mutableListOf<MutableList<TerminalChar>>()
        var currentLogicalLine = mutableListOf<TerminalChar>()
        
        fun processPhysicalLine(line: Array<TerminalChar>, isWrapped: Boolean) {
            val effectiveLength = if (isWrapped) {
                line.size
            } else {
                findLastNonEmptyIndex(line) + 1
            }
            
            for (i in 0 until effectiveLength) {
                currentLogicalLine.add(line[i])
            }
            
            if (!isWrapped) {
                logicalLines.add(currentLogicalLine)
                currentLogicalLine = mutableListOf()
            }
        }
        
        for (i in historyBuffer.indices) {
            processPhysicalLine(historyBuffer[i], historyWrapped[i])
        }
        
        for (i in 0 until screenHeight) {
            processPhysicalLine(screenBuffer[i], lineWrapped[i])
        }
        
        if (currentLogicalLine.isNotEmpty()) {
            logicalLines.add(currentLogicalLine)
        }
        
        return logicalLines
    }
    
    private fun findLastNonEmptyIndex(line: Array<TerminalChar>): Int {
        for (i in line.size - 1 downTo 0) {
            val c = line[i]
            if (c.char != ' ' || c.bgColor != Color.BLACK || c.isInverse || c.isUnderline || c.isStrikethrough) {
                return i
            }
        }
        return -1
    }
    
    private fun trimTrailingEmptyLines(logicalLines: MutableList<MutableList<TerminalChar>>) {
        while (logicalLines.isNotEmpty()) {
            val lastLine = logicalLines.last()
            if (lastLine.isEmpty() || lastLine.all { 
                it.char == ' ' && it.bgColor == Color.BLACK && !it.isInverse && !it.isUnderline 
            }) {
                logicalLines.removeAt(logicalLines.size - 1)
            } else {
                break
            }
        }
    }
    
    private fun rewrapLogicalLines(
        logicalLines: List<List<TerminalChar>>,
        newWidth: Int
    ): Pair<List<Array<TerminalChar>>, List<Boolean>> {
        val physicalLines = mutableListOf<Array<TerminalChar>>()
        val wrappedFlags = mutableListOf<Boolean>()
        
        for (logicalLine in logicalLines) {
            if (logicalLine.isEmpty()) {
                physicalLines.add(Array(newWidth) { TerminalChar() })
                wrappedFlags.add(false)
                continue
            }
            
            var offset = 0
            while (offset < logicalLine.size) {
                val chunkSize = (logicalLine.size - offset).coerceAtMost(newWidth)
                val chunk = Array(newWidth) { TerminalChar() }
                
                for (i in 0 until chunkSize) {
                    chunk[i] = logicalLine[offset + i]
                }
                
                offset += chunkSize
                val isWrapped = offset < logicalLine.size
                
                physicalLines.add(chunk)
                wrappedFlags.add(isWrapped)
            }
        }
        
        return Pair(physicalLines, wrappedFlags)
    }
    
    private fun distributeToBuffers(
        physicalLines: List<Array<TerminalChar>>,
        wrappedFlags: List<Boolean>
    ) {
        historyBuffer.clear()
        historyWrapped.clear()
        screenBuffer = Array(screenHeight) { Array(screenWidth) { TerminalChar() } }
        lineWrapped = BooleanArray(screenHeight) { false }
        
        val totalLines = physicalLines.size
        
        if (totalLines <= screenHeight) {
            for (i in 0 until totalLines) {
                screenBuffer[i] = physicalLines[i]
                lineWrapped[i] = wrappedFlags[i]
            }
            cursorY = (totalLines - 1).coerceAtLeast(0)
        } else {
            val historyCount = totalLines - screenHeight
            val startHistoryIdx = (historyCount - historySize).coerceAtLeast(0)
            
            // historySize
            for (i in startHistoryIdx until historyCount) {
                historyBuffer.add(physicalLines[i])
                historyWrapped.add(wrappedFlags[i])
            }
            
            // screenHeight
            for (i in 0 until screenHeight) {
                screenBuffer[i] = physicalLines[historyCount + i]
                lineWrapped[i] = wrappedFlags[historyCount + i]
            }
            
            cursorY = screenHeight - 1
        }
    }
    
    // === ===
    
        fun addChangeListener(listener: () -> Unit) {
        changeListeners.add(listener)
    }
    
    fun removeChangeListener(listener: () -> Unit) {
        changeListeners.remove(listener)
    }
    
    fun addNewOutputListener(listener: () -> Unit) {
        newOutputListeners.add(listener)
    }
    
    fun removeNewOutputListener(listener: () -> Unit) {
        newOutputListeners.remove(listener)
    }
    
    private fun notifyChange() {
        changeListeners.forEach { it() }
    }
    
    private fun notifyNewOutput() {
        newOutputListeners.forEach { it() }
    }
} 
