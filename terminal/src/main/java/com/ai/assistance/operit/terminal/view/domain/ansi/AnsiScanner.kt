package com.ai.assistance.operit.terminal.view.domain.ansi

/**
 * ANSI 
 *  ANSI 
 */
class AnsiScanner(private val input: String) {
    private var position = 0
    
        fun hasNext(): Boolean = position < input.length
    
        fun getPosition(): Int = position
    
        fun peek(): Char? = if (hasNext()) input[position] else null
    
        fun peek(offset: Int): Char? {
        val index = position + offset
        return if (index < input.length) input[index] else null
    }
    
    fun next(): Char? {
        return if (hasNext()) input[position++] else null
    }
    
    /**
     *  ANSI 
     */
    fun scanNext(): AnsiSequence? {
        if (!hasNext()) return null
        
        val char = peek() ?: return null
        
        return when {
            char == '\u001B' -> scanEscapeSequence()
            char.code in 0..31 || char == '\u007F' -> scanControlCharacter()
            else -> AnsiSequence.Text(next()!!)
        }
    }
    
    fun scanAll(): List<AnsiSequence> {
        val sequences = mutableListOf<AnsiSequence>()
        while (hasNext()) {
            scanNext()?.let { sequences.add(it) }
        }
        return sequences
    }
    
    /**
     *  ( ESC )
     */
    private fun scanEscapeSequence(): AnsiSequence {
        val start = position
        next() // ESC
        
        return when (peek()) {
            '[' -> scanCSI()
            ']' -> scanOSC()
            'P' -> scanDCS()
            // DEC
            '7' -> { next(); AnsiSequence.SingleEscape('7')
            } // DECSC
            '8' -> { next(); AnsiSequence.SingleEscape('8')
            } // DECRC
            'c' -> { next(); AnsiSequence.SingleEscape('c')
            } // RIS
            'D' -> { next(); AnsiSequence.SingleEscape('D')
            } // IND
            'E' -> { next(); AnsiSequence.SingleEscape('E')
            } // NEL
            'H' -> { next(); AnsiSequence.SingleEscape('H')
            } // HTS
            'M' -> { next(); AnsiSequence.SingleEscape('M')
            } // RI
            'Z' -> { next(); AnsiSequence.SingleEscape('Z')
            } // DECID
            else -> {
                val unknownChar = next()
                AnsiSequence.Unknown(input.substring(start, position))
            }
        }
    }
    
    /**
     *  CSI  (Control Sequence Introducer)
     * : ESC [ [params] [intermediates] command
     */
    private fun scanCSI(): AnsiSequence {
        next() // '['
        
        // ( ? )
        val isPrivate = peek() == '?'
        if (isPrivate) next()
        
        // ()
        val paramsBuilder = StringBuilder()
        while (hasNext()) {
            val char = peek()!!
            if (char.isDigit() || char == ';') {
                paramsBuilder.append(next())
            } else {
                break
            }
        }
        
        // (0x20-0x2F)
        val intermediates = StringBuilder()
        while (hasNext()) {
            val char = peek()!!
            if (char.code in 0x20..0x2F) {
                intermediates.append(next())
            } else {
                break
            }
        }
        
        // (0x40-0x7E)
        val command = if (hasNext()) {
            val char = peek()!!
            if (char.code in 0x40..0x7E) {
                next()!!
            } else {
                '?'
            }
        } else {
            '?'
        }
        
        val params = if (paramsBuilder.isEmpty()) {
            emptyList()
        } else {
            paramsBuilder.toString()
                .split(';')
                .map { it.toIntOrNull() ?: 0 }
        }
        
        return AnsiSequence.CSI(
            params = params,
            command = command,
            intermediates = intermediates.toString(),
            private = isPrivate
        )
    }
    
    /**
     *  OSC  (Operating System Command)
     * : ESC ] command ; data BEL  ESC ] command ; data ESC \
     */
    private fun scanOSC(): AnsiSequence {
        next() // ']'
        
        val commandBuilder = StringBuilder()
        while (hasNext()) {
            val char = peek()!!
            if (char.isDigit()) {
                commandBuilder.append(next())
            } else {
                break
            }
        }
        
        val command = commandBuilder.toString().toIntOrNull() ?: 0
        
        if (peek() == ';') next()
        
        // BEL (\u0007) ST (ESC \)
        val dataBuilder = StringBuilder()
        while (hasNext()) {
            val char = peek()!!
            if (char == '\u0007') { // BEL
                next()
                break
            } else if (char == '\u001B' && peek(1) == '\\') { // ST
                next()
                next()
                break
            } else {
                dataBuilder.append(next())
            }
        }
        
        return AnsiSequence.OSC(command, dataBuilder.toString())
    }
    
    /**
     *  DCS  (Device Control String)
     * : ESC P data ST
     */
    private fun scanDCS(): AnsiSequence {
        next() // 'P'
        
        // ST (ESC \)
        val dataBuilder = StringBuilder()
        while (hasNext()) {
            val char = peek()!!
            if (char == '\u001B' && peek(1) == '\\') {
                next()
                next()
                break
            } else {
                dataBuilder.append(next())
            }
        }
        
        return AnsiSequence.DCS(dataBuilder.toString())
    }
    
    private fun scanControlCharacter(): AnsiSequence {
        val char = next()!!
        val type = when (char) {
            '\u0000' -> ControlCharType.NULL
            '\u0007' -> ControlCharType.BELL
            '\b' -> ControlCharType.BACKSPACE
            '\t' -> ControlCharType.TAB
            '\n' -> ControlCharType.LINE_FEED
            '\u000B' -> ControlCharType.VERTICAL_TAB
            '\u000C' -> ControlCharType.FORM_FEED
            '\r' -> ControlCharType.CARRIAGE_RETURN
            '\u001B' -> ControlCharType.ESCAPE
            '\u007F' -> ControlCharType.DELETE
            else -> ControlCharType.OTHER
        }
        return AnsiSequence.ControlChar(type, char)
    }
}

/**
 * ANSI 
 */
object AnsiUtils {
    /**
     *  ANSI 
     */
    fun containsAnsiSequences(text: String): Boolean {
        return text.contains('\u001B')
    }
    
    /**
     *  ANSI 
     */
    fun stripAnsi(text: String): String {
        if (!containsAnsiSequences(text)) return text
        
        val scanner = AnsiScanner(text)
        val result = StringBuilder()
        
        while (scanner.hasNext()) {
            when (val seq = scanner.scanNext()) {
                is AnsiSequence.Text -> result.append(seq.char)
                is AnsiSequence.ControlChar -> {
                    when (seq.type) {
                        ControlCharType.TAB,
                        ControlCharType.LINE_FEED,
                        ControlCharType.CARRIAGE_RETURN -> result.append(seq.char)
                        else -> {}
                    }
                }
                else -> {} // ANSI
            }
        }
        
        return result.toString()
    }
    
    /**
     *  ANSI 
     */
    fun isProgressLine(text: String): Boolean {
        if (!containsAnsiSequences(text)) {
            return text.contains('\r') && !text.contains('\n')
        }
        
        val scanner = AnsiScanner(text)
        
        while (scanner.hasNext()) {
            when (val seq = scanner.scanNext()) {
                is AnsiSequence.ControlChar -> {
                    if (seq.type == ControlCharType.CARRIAGE_RETURN && !text.contains('\n')) {
                        return true
                    }
                }
                is AnsiSequence.CSI -> {
                    when (seq.command) {
                        'K' -> return true
                        'A', 'B', 'C', 'D', 'G' -> return true
                        's', 'u' -> return true // / (ANSI.SYS)
                        else -> {}
                    }
                }
                is AnsiSequence.SingleEscape -> {
                    // DEC /
                    if (seq.char == '7' || seq.char == '8') {
                        return true
                    }
                }
                else -> {}
            }
        }
        
        return false
    }
    
    fun detectFullscreenMode(text: String): FullscreenMode? {
        val scanner = AnsiScanner(text)
        
        while (scanner.hasNext()) {
            when (val seq = scanner.scanNext()) {
                is AnsiSequence.CSI -> {
                    if (seq.private && (seq.command == 'h' || seq.command == 'l')) {
                        if (seq.params.contains(1049)) {
                            return if (seq.command == 'h') {
                                FullscreenMode.ENTER
                            } else {
                                FullscreenMode.EXIT
                            }
                        }
                    }
                }
                else -> {}
            }
        }
        
        return null
    }
    
    enum class FullscreenMode {
        ENTER, EXIT
    }
} 