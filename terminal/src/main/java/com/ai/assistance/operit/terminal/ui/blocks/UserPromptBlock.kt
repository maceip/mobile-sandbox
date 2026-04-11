package com.ai.assistance.operit.terminal.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun UserPromptBlock(text: String) {
    Text(
        text = "> $text",
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF131C26))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        color = Color(0xFFEAF2FB),
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
    )
}
