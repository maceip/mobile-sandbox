package com.ai.assistance.operit.terminal.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AgentTextBlock(
    text: String,
    isCollapsed: Boolean,
    onToggle: () -> Unit
) {
    val lines = text.lines()
    val preview = if (isCollapsed) lines.take(2).joinToString("\n") else text
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = preview,
            color = Color(0xFFD7E3F4),
            fontSize = 15.sp,
            maxLines = if (isCollapsed) 2 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis
        )
        if (isCollapsed && lines.size > 2) {
            Text(
                text = "⤷ ${lines.size - 2} more lines",
                color = Color(0xFF7BA8D8),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
