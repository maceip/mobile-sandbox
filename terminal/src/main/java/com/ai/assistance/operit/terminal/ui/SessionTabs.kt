package com.ai.assistance.operit.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.terminal.data.TerminalSessionData

@Composable
fun SessionTabs(
    sessions: List<TerminalSessionData>,
    currentSessionId: String?,
    onSessionClick: (String) -> Unit,
    onAddSession: () -> Unit
) {
    Row(
        modifier = Modifier
            .background(Color(0xFF0E141B))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sessions.forEachIndexed { index, session ->
            val active = session.id == currentSessionId
            Text(
                text = "${index + 1}",
                modifier = Modifier
                    .background(
                        color = if (active) Color(0xFF1F7A4D) else Color(0xFF223242),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSessionClick(session.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = Color.White
            )
        }
        Text(
            text = "+",
            modifier = Modifier
                .background(Color(0xFF223242), RoundedCornerShape(8.dp))
                .clickable(onClick = onAddSession)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = Color.White
        )
    }
}
