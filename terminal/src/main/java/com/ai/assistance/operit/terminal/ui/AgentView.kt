package com.ai.assistance.operit.terminal.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.terminal.ui.blocks.AgentTextBlock
import com.ai.assistance.operit.terminal.ui.blocks.CommandBlock
import com.ai.assistance.operit.terminal.ui.blocks.DiffBlock
import com.ai.assistance.operit.terminal.ui.blocks.ErrorBlock
import com.ai.assistance.operit.terminal.ui.blocks.UserPromptBlock

@Composable
fun AgentView(
    sessionId: String?,
    blocks: SnapshotStateList<AgentBlock>,
    onToggleText: (Int) -> Unit,
    onToggleCommand: (Int) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
        itemsIndexed(blocks) { index, block ->
            when (block) {
                is AgentBlock.UserPrompt -> UserPromptBlock(block.text)
                is AgentBlock.AgentText -> AgentTextBlock(
                    text = block.text,
                    isCollapsed = block.isCollapsed,
                    onToggle = { if (sessionId != null) onToggleText(index) }
                )
                is AgentBlock.Command -> {
                    if (block.exitCode != null && block.exitCode != 0) {
                        ErrorBlock(
                            command = block.command,
                            output = block.output,
                            durationMs = block.durationMs
                        )
                    } else {
                        CommandBlock(
                            command = block.command,
                            output = block.output,
                            exitCode = block.exitCode,
                            durationMs = block.durationMs,
                            isExpanded = block.isExpanded,
                            onToggle = { if (sessionId != null) onToggleCommand(index) }
                        )
                    }
                }
                is AgentBlock.Diff -> DiffBlock(block.filePath, block.hunks)
            }
        }
    }
}
