package com.ai.assistance.operit.terminal.tui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator
import com.ai.assistance.operit.terminal.view.domain.ansi.TerminalChar
import kotlinx.coroutines.delay

/**
 * Compose UI that renders a TUI application on a phone screen.
 *
 * Instead of trying to display an 80-column terminal on a 40-column
 * phone, this view:
 * 1. Reads the shadow emulator (always 80x24)
 * 2. Uses [TuiScraper] to decompose the screen into regions
 * 3. Renders each region as a native Compose widget:
 *    - Status bars: pinned top/bottom with inverse styling
 *    - Content areas: vertically scrollable, horizontally scrollable
 *    - Menus: horizontal scrollable row
 *    - Separators: thin divider lines
 */
@Composable
fun TuiBridgeView(
    shadowEmulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier
) {
    var layout by remember { mutableStateOf<TuiLayout?>(null) }

    // Re-scrape whenever the shadow emulator notifies of changes
    LaunchedEffect(shadowEmulator) {
        // Poll the emulator. The emulator notifies via change listeners,
        // but from Compose we poll at a reasonable interval to avoid
        // over-recomposition. The canvas view already handles high-FPS
        // rendering; this view is for structured display.
        while (true) {
            layout = TuiScraper.scrape(shadowEmulator)
            delay(250) // 4 Hz refresh - TUI content doesn't need 60 FPS
        }
    }

    val currentLayout = layout
    if (currentLayout == null || currentLayout.regions.isEmpty()) {
        // Fallback while waiting for first scrape
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
        )
        return
    }

    val contentScrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Pinned top bar
        currentLayout.topBar?.let { bar ->
            RegionRow(
                region = bar.region,
                role = bar.role,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Scrollable middle content
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(contentScrollState)
        ) {
            for (tagged in currentLayout.regions) {
                when (tagged.role) {
                    RegionRole.STATUS_BAR_TOP, RegionRole.STATUS_BAR_BOTTOM -> {
                        // Already rendered pinned, skip in scroll area
                    }
                    RegionRole.SEPARATOR -> {
                        HorizontalDivider(
                            color = Color(0xFF3A3A3A),
                            thickness = 1.dp
                        )
                    }
                    RegionRole.MENU_BAR -> {
                        RegionRow(
                            region = tagged.region,
                            role = tagged.role,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    RegionRole.CONTENT -> {
                        RegionBlock(
                            region = tagged.region,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Pinned bottom bar
        currentLayout.bottomBar?.let { bar ->
            RegionRow(
                region = bar.region,
                role = bar.role,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Render a single-row region (status bar, menu bar) as a horizontally
 * scrollable text row with ANSI-derived styling.
 */
@Composable
private fun RegionRow(
    region: ScreenRegion,
    role: RegionRole,
    modifier: Modifier = Modifier
) {
    val hScrollState = rememberScrollState()
    val bgColor = when (role) {
        RegionRole.STATUS_BAR_TOP, RegionRole.STATUS_BAR_BOTTOM -> Color(0xFF1A3A5C)
        RegionRole.MENU_BAR -> Color(0xFF1A1A2E)
        else -> Color.Black
    }

    Box(
        modifier = modifier
            .background(bgColor)
            .horizontalScroll(hScrollState)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        for (row in region.rows) {
            Text(
                text = rowToAnnotatedString(row),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Visible,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Render a multi-row content region as horizontally scrollable text block.
 */
@Composable
private fun RegionBlock(
    region: ScreenRegion,
    modifier: Modifier = Modifier
) {
    val hScrollState = rememberScrollState()

    Column(
        modifier = modifier
            .horizontalScroll(hScrollState)
            .padding(horizontal = 4.dp)
    ) {
        for (row in region.rows) {
            Text(
                text = rowToAnnotatedString(row),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Visible
            )
        }
    }
}

/**
 * Convert a row of [TerminalChar]s into a Compose [AnnotatedString]
 * preserving foreground color, bold, italic, underline, and strikethrough.
 */
private fun rowToAnnotatedString(row: Array<TerminalChar>): AnnotatedString {
    return buildAnnotatedString {
        // Group consecutive characters with identical attributes for efficiency
        var i = 0
        while (i < row.size) {
            val startAttrs = row[i].attributes
            val start = i
            while (i < row.size && row[i].attributes == startAttrs) {
                i++
            }

            val span = SpanStyle(
                color = androidColorToComposeColor(
                    if (startAttrs.isInverse) startAttrs.bgColor else startAttrs.fgColor
                ),
                background = androidColorToComposeColor(
                    if (startAttrs.isInverse) startAttrs.fgColor else startAttrs.bgColor
                ),
                fontWeight = if (startAttrs.isBold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (startAttrs.isItalic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = when {
                    startAttrs.isUnderline && startAttrs.isStrikethrough ->
                        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    startAttrs.isUnderline -> TextDecoration.Underline
                    startAttrs.isStrikethrough -> TextDecoration.LineThrough
                    else -> TextDecoration.None
                }
            )

            withStyle(span) {
                for (j in start until i) {
                    append(row[j].char)
                }
            }
        }
    }
}

/** Map an android.graphics.Color int to a Compose Color. */
private fun androidColorToComposeColor(color: Int): Color {
    return Color(
        red = android.graphics.Color.red(color),
        green = android.graphics.Color.green(color),
        blue = android.graphics.Color.blue(color),
        alpha = android.graphics.Color.alpha(color)
    )
}
