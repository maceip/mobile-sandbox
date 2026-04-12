package com.ai.assistance.operit.terminal.tui

import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator
import com.ai.assistance.operit.terminal.view.domain.ansi.TerminalChar

/**
 * Extracts meaningful UI regions from a shadow emulator's screen buffer.
 *
 * TUI apps (htop, vim, less, tmux) render into a standard 80x24 grid.
 * This scraper reads that grid and decomposes it into tagged regions
 * (status bars, content areas, menus) that [TuiBridgeView] can render
 * as native Compose widgets on a narrow phone screen.
 *
 * Detection heuristics:
 * - **Status bars**: rows where >50% of characters have inverse video
 * - **Separators**: rows composed primarily of box-drawing characters
 * - **Menus**: rows with multiple highlighted segments (bold/inverse spans)
 * - **Content**: everything else, grouped into contiguous blocks
 */
object TuiScraper {

    private val BOX_CHARS = setOf(
        '─', '━', '│', '┃', '┌', '┐', '└', '┘', '├', '┤', '┬', '┴', '┼',
        '╔', '╗', '╚', '╝', '╠', '╣', '╦', '╩', '╬',
        '═', '║', '╒', '╓', '╕', '╖', '╘', '╙', '╛', '╜', '╞', '╟', '╡', '╢', '╤', '╥', '╧', '╨', '╪', '╫',
        '─', '━', '┄', '┅', '┆', '┇', '┈', '┉', '┊', '┋',
        '▀', '▄', '█', '▌', '▐', '░', '▒', '▓',
        '-', '|', '+', '='
    )

    /**
     * Scrape the shadow emulator's current screen buffer into a [TuiLayout].
     */
    fun scrape(emulator: AnsiTerminalEmulator): TuiLayout {
        val screen = emulator.getScreenContent()
        val rows = screen.size
        if (rows == 0) return TuiLayout(emptyList(), 0, 0)
        val cols = screen[0].size

        // Classify each row
        val rowRoles = Array(rows) { classifyRow(screen[it], cols) }

        // Build contiguous tagged regions
        val regions = mutableListOf<TaggedRegion>()
        var blockStart = 0

        while (blockStart < rows) {
            val role = rowRoles[blockStart]
            var blockEnd = blockStart + 1

            // For content, group contiguous content rows together
            if (role == RegionRole.CONTENT) {
                while (blockEnd < rows && rowRoles[blockEnd] == RegionRole.CONTENT) {
                    blockEnd++
                }
            }
            // Status bars, menus, separators stay as single-row regions

            val regionRows = (blockStart until blockEnd).map { screen[it].copyOf() }
            regions += TaggedRegion(
                role = role,
                region = ScreenRegion(
                    rows = regionRows,
                    startRow = blockStart,
                    startCol = 0,
                    width = cols,
                    height = blockEnd - blockStart
                )
            )

            blockStart = blockEnd
        }

        // Post-process: promote first/last status-like regions
        promoteEdgeStatusBars(regions)

        return TuiLayout(
            regions = regions,
            totalRows = rows,
            totalCols = cols
        )
    }

    /**
     * Classify a single row by its visual characteristics.
     */
    private fun classifyRow(row: Array<TerminalChar>, width: Int): RegionRole {
        if (width == 0) return RegionRole.CONTENT

        var inverseCount = 0
        var boxCount = 0
        var boldInverseSpans = 0
        var nonEmptyCount = 0
        var prevWasHighlight = false

        for (i in 0 until width) {
            val ch = row[i]
            val c = ch.char

            if (c != ' ' || ch.bgColor != android.graphics.Color.BLACK) {
                nonEmptyCount++
            }

            if (ch.isInverse) {
                inverseCount++
                if (!prevWasHighlight) {
                    boldInverseSpans++
                    prevWasHighlight = true
                }
            } else {
                prevWasHighlight = false
            }

            if (c in BOX_CHARS) {
                boxCount++
            }
        }

        // Mostly-empty rows are content
        if (nonEmptyCount == 0) return RegionRole.CONTENT

        val inverseRatio = inverseCount.toFloat() / width
        val boxRatio = boxCount.toFloat() / width

        // Separator: >60% box-drawing characters
        if (boxRatio > 0.6f) return RegionRole.SEPARATOR

        // Status bar: >50% inverse video
        if (inverseRatio > 0.5f) return RegionRole.STATUS_BAR_TOP // promoted later

        // Menu bar: multiple highlighted spans but not full inverse
        if (boldInverseSpans >= 3 && inverseRatio > 0.15f) return RegionRole.MENU_BAR

        return RegionRole.CONTENT
    }

    /**
     * Promote top-most and bottom-most status-bar-like rows to their proper roles.
     *
     * After initial classification every inverse row is STATUS_BAR_TOP.
     * This pass renames the last one to STATUS_BAR_BOTTOM.
     */
    private fun promoteEdgeStatusBars(regions: MutableList<TaggedRegion>) {
        if (regions.isEmpty()) return

        // Find the last status-bar region and rename it to BOTTOM
        val lastStatusIdx = regions.indexOfLast {
            it.role == RegionRole.STATUS_BAR_TOP
        }
        if (lastStatusIdx > 0) {
            val old = regions[lastStatusIdx]
            regions[lastStatusIdx] = old.copy(role = RegionRole.STATUS_BAR_BOTTOM)
        }
    }

    /**
     * Render a [ScreenRegion] back to a plain-text string for debugging.
     */
    fun regionToString(region: ScreenRegion): String {
        return region.rows.joinToString("\n") { row ->
            String(row.map { it.char }.toCharArray()).trimEnd()
        }
    }
}
