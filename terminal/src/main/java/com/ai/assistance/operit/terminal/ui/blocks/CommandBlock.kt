package com.ai.assistance.operit.terminal.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CommandBlock(
    command: String,
    output: String,
    exitCode: Int?,
    durationMs: Long?,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val statusIcon = when (exitCode) {
        null -> "⏳"
        0 -> "🟢"
        else -> "🔴"
    }
    val accent = when (exitCode) {
        null -> Color(0xFF40698C)
        0 -> Color(0xFF1F7A4D)
        else -> Color(0xFF9C3C45)
    }
    val preview = output.lines().takeLast(2).joinToString("\n")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(accent.copy(alpha = 0.18f))
            .clickable(onClick = onToggle)
            .padding(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "$statusIcon $command",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = durationMs?.let { formatDuration(it) } ?: "running",
                color = Color(0xFFB8C9D9),
                fontSize = 12.sp
            )
        }
        if (output.isNotBlank()) {
            Text(
                text = if (isExpanded || exitCode != null && exitCode != 0) output else preview,
                color = Color(0xFFD7E3F4),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    return if (durationMs >= 1000) {
        "${durationMs / 1000.0}s"
    } else {
        "${durationMs}ms"
    }
}
