package com.ai.assistance.operit.terminal.tui

import com.ai.assistance.operit.terminal.view.domain.ansi.TerminalChar

/**
 * A rectangular slice of a terminal screen buffer.
 *
 * Used by [TuiScraper] to extract meaningful UI regions (status bars,
 * scrollable content, menus) from a full-width shadow emulator so they
 * can be rendered as native Compose widgets on a phone screen.
 */
data class ScreenRegion(
    val rows: List<Array<TerminalChar>>,
    val startRow: Int,
    val startCol: Int,
    val width: Int,
    val height: Int
)

/**
 * The role a [ScreenRegion] plays in the TUI layout.
 */
enum class RegionRole {
    /** Top status/title bar (often inverse video). */
    STATUS_BAR_TOP,
    /** Bottom status/info bar (often inverse video). */
    STATUS_BAR_BOTTOM,
    /** Menu or tab bar (row with highlighted segments). */
    MENU_BAR,
    /** Primary scrollable content area. */
    CONTENT,
    /** Separator row (box-drawing characters). */
    SEPARATOR
}

/**
 * A tagged region: a [ScreenRegion] annotated with its detected [RegionRole].
 */
data class TaggedRegion(
    val role: RegionRole,
    val region: ScreenRegion
)

/**
 * Complete decomposition of a TUI screen into native-renderable regions.
 *
 * Produced by [TuiScraper.scrape] from the shadow emulator's screen buffer.
 * Consumed by [TuiBridgeView] to project each region into a Compose widget
 * appropriate for its role.
 */
data class TuiLayout(
    val regions: List<TaggedRegion>,
    val totalRows: Int,
    val totalCols: Int
) {
    val topBar: TaggedRegion? get() = regions.firstOrNull { it.role == RegionRole.STATUS_BAR_TOP }
    val bottomBar: TaggedRegion? get() = regions.lastOrNull { it.role == RegionRole.STATUS_BAR_BOTTOM }
    val content: List<TaggedRegion> get() = regions.filter { it.role == RegionRole.CONTENT }
    val menus: List<TaggedRegion> get() = regions.filter { it.role == RegionRole.MENU_BAR }
}
