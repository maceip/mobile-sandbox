package com.ai.assistance.operit.terminal.view.canvas

import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache

class TextMetrics(
    private val paint: Paint,
    config: RenderConfig
) {
    private val charWidthCache = LruCache<Char, Float>(256)
    private val glyphCache = LruCache<Char, Int>(256) // 0: Default, 1: Nerd, 2: None
    private var currentTypeface: Typeface = config.typeface
    private var nerdTypeface: Typeface? = null // Nerd Font
    
    var charWidth: Float = 0f
        private set
    var charHeight: Float = 0f
        private set
    var charBaseline: Float = 0f
        private set
    
    init {
        updateFromRenderConfig(config)
    }

    /**
     *  RenderConfig 
     */
    fun updateFromRenderConfig(config: RenderConfig) {
        paint.textSize = config.fontSize
        currentTypeface = config.typeface
        paint.typeface = currentTypeface

        // 'M'
        charWidth = paint.measureText("M")

        val fontMetrics = paint.fontMetrics
        charHeight = fontMetrics.descent - fontMetrics.ascent
        charBaseline = -fontMetrics.ascent

        charWidthCache.evictAll()
        glyphCache.evictAll()
    }
    
    /**
     *  Nerd Font 
     */
    fun setNerdTypeface(typeface: Typeface?) {
        nerdTypeface = typeface
        charWidthCache.evictAll()
        glyphCache.evictAll()
    }
    
    /**
     *
     * @param char 
     * @return 0: Default, 1: Nerd, 2: Fallback/None
     */
    fun resolveFontType(char: Char): Int {
        glyphCache.get(char)?.let { return it }

        val charStr = char.toString()
        
        // 1.
        paint.typeface = currentTypeface
        if (paint.hasGlyph(charStr)) {
            glyphCache.put(char, 0)
            return 0
        }
        
        // 2. Nerd
        nerdTypeface?.let { nerd ->
            paint.typeface = nerd
            if (paint.hasGlyph(charStr)) {
                glyphCache.put(char, 1)
                return 1
            }
        }
        
        // 3.
        glyphCache.put(char, 2)
        return 2
    }

    /**
     *  Paint  Typeface
     */
    fun setFont(fontType: Int) {
        when (fontType) {
            1 -> paint.typeface = nerdTypeface ?: currentTypeface
            else -> paint.typeface = currentTypeface
        }
    }

    /**
     * NerdPaint
     * @param char 
     * @return  false
     */
    fun selectTypefaceForChar(char: Char): Boolean {
        val type = resolveFontType(char)
        setFont(type)
        return type != 2
    }
    
    fun getCharWidth(char: Char): Float {
        return charWidthCache.get(char) ?: run {
            val width = paint.measureText(char.toString())
            charWidthCache.put(char, width)
            width
        }
    }
    
    /**
     *
     * 2
     */
    fun isWideChar(char: Char): Boolean {
        val code = char.code
        return when {
            // CJK
            code in 0x4E00..0x9FFF -> true
            // CJKA
            code in 0x3400..0x4DBF -> true
            // CJKB
            code in 0x20000..0x2A6DF -> true
            // CJKC
            code in 0x2A700..0x2B73F -> true
            // CJKD
            code in 0x2B740..0x2B81F -> true
            // CJKE
            code in 0x2B820..0x2CEAF -> true
            code in 0x3040..0x309F -> true
            code in 0x30A0..0x30FF -> true
            code in 0xAC00..0xD7AF -> true
            code in 0xFF00..0xFFEF -> true
            code in 0x3000..0x303F -> true
            else -> false
        }
    }
    
    /**
     * 12
     * 21
     */
    fun getCellWidth(char: Char): Int {
        return if (isWideChar(char)) 2 else 1
    }
    
    fun applyStyle(isBold: Boolean, isItalic: Boolean) {
        val style = when {
            isBold && isItalic -> Typeface.BOLD_ITALIC
            isBold -> Typeface.BOLD
            isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        // Nerd Font /
        // selectTypefaceForChar
        val baseTypeface = currentTypeface
        paint.typeface = Typeface.create(baseTypeface, style)
    }
    
    fun resetStyle() {
        paint.typeface = currentTypeface
    }
}

