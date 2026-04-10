package com.example.orderfiledemo.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class ComposeSandboxActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComposeSandboxScreen()
        }
    }
}

@Composable
private fun ComposeSandboxScreen() {
    val tasks = listOf(
        SandboxTask("Workspace", "Project tree, recent files, pinned worktrees"),
        SandboxTask("Editor", "Compose text surface, diagnostics, diff affordances"),
        SandboxTask("Console", "Command stream, task output, runtime probes"),
        SandboxTask("Runtime", "Python and shell actions backed by JNI state"),
    )

    Surface(color = Color(0xFFF4EFE6)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFF4EFE6), Color(0xFFD7E4DA), Color(0xFFC8D2DE))
                    )
                )
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                HeroBlock()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    RailCard(
                        title = "Compose Rail",
                        subtitle = "A safe Kotlin-first place to iterate on frontend structure before replacing the legacy GameActivity screen.",
                        modifier = Modifier.weight(0.95f)
                    )
                    StatusCard(modifier = Modifier.weight(1.55f))
                }
                TaskDeck(tasks = tasks)
            }
        }
    }
}

@Composable
private fun HeroBlock() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "COMPOSE FRONTEND",
            color = Color(0xFF7A3E12),
            fontSize = 12.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Cory Sandbox",
            color = Color(0xFF17202A),
            fontSize = 34.sp,
            lineHeight = 38.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "This activity is intentionally isolated from the current JNI shell so UI work can move without dragging the native toolchain into every change.",
            color = Color(0xFF45515E),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun RailCard(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(172.dp),
        color = Color(0xFFF9F5ED),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                color = Color(0xFF7A3E12),
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp
            )
            Text(
                text = subtitle,
                color = Color(0xFF24313D),
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
private fun StatusCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(172.dp),
        color = Color(0xFF1D2B36),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Frontend Status",
                color = Color(0xFF9ED8B4),
                fontWeight = FontWeight.SemiBold
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusLine("Build payloads can now come from S3 archives")
                StatusLine("libnode link is optional by default")
                StatusLine("Compose toolchain is enabled in the app module")
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(Color(0xFF9ED8B4), RoundedCornerShape(50))
        )
        Text(
            text = text,
            color = Color(0xFFF3F0E8),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun TaskDeck(tasks: List<SandboxTask>) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(tasks) { task ->
            Surface(
                color = Color(0xFFFFFBF5),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = task.title,
                            color = Color(0xFF1F2A33),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = task.description,
                            color = Color(0xFF5C6772),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        text = "ready",
                        color = Color(0xFF7A3E12),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Immutable
private data class SandboxTask(
    val title: String,
    val description: String,
)
