package com.ai.assistance.operit.terminal.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.terminal.ui.DiffHunk

@Composable
fun DiffBlock(filePath: String, hunks: List<DiffHunk>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Color(0xFF1B2530))
            .padding(14.dp)
    ) {
        Text(
            text = filePath,
            color = Color(0xFFEAF2FB),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp
        )
        hunks.forEach { hunk ->
            Text(
                text = hunk.header,
                color = Color(0xFF7BA8D8),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            hunk.lines.forEach { line ->
                val color = when {
                    line.startsWith("+") -> Color(0xFF74C69D)
                    line.startsWith("-") -> Color(0xFFF28482)
                    else -> Color(0xFFD7E3F4)
                }
                Text(
                    text = line,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }
        }
    }
}
