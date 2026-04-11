package com.ai.assistance.operit.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.terminal.TerminalEnv

/**
 * Adaptive scaffold: single pane on phone, dual pane on foldable/tablet.
 * 600dp width breakpoint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoryScaffold(env: TerminalEnv) {
    val context = LocalContext.current
    val terminalManager = remember(context) {
        com.ai.assistance.operit.terminal.TerminalManager.getInstance(context)
    }
    val agentUiState = rememberAgentUiState(terminalManager)
    val blocks = agentUiState.blocks(env.currentSessionId)
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isDualPane = screenWidthDp >= 600

    Scaffold(
        containerColor = Color(0xFF0B1016),
        topBar = {
            SessionTabs(
                sessions = env.sessions,
                currentSessionId = env.currentSessionId,
                onSessionClick = env::onSwitchSession,
                onAddSession = env::onNewSession
            )
        },
        bottomBar = {
            InputBar(env)
        }
    ) { padding ->
        if (isDualPane) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0B1016))
                    .padding(padding)
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                        .background(Color(0xFF0B1016))
                ) {
                    AgentView(
                        sessionId = env.currentSessionId,
                        blocks = blocks,
                        onToggleText = { index ->
                            env.currentSessionId?.let { agentUiState.toggleText(it, index) }
                        },
                        onToggleCommand = { index ->
                            env.currentSessionId?.let { agentUiState.toggleCommand(it, index) }
                        }
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight()
                        .background(Color(0xFF11161C))
                ) {
                    Text(
                        text = "Terminal",
                        color = Color(0xFF6B7D93),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    TerminalPane(
                        env = env,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0B1016))
                    .padding(padding)
            ) {
                AgentView(
                    sessionId = env.currentSessionId,
                    blocks = blocks,
                    onToggleText = { index ->
                        env.currentSessionId?.let { agentUiState.toggleText(it, index) }
                    },
                    onToggleCommand = { index ->
                        env.currentSessionId?.let { agentUiState.toggleCommand(it, index) }
                    }
                )
            }
        }
    }
}
