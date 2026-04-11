package com.ai.assistance.operit.terminal.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.terminal.TerminalEnv

@Composable
fun InputBar(env: TerminalEnv) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        OutlinedTextField(
            value = env.command,
            onValueChange = env::onCommandChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("What should I do next...") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                env.onSendInput(env.command, true)
            })
        )
        Button(
            onClick = { env.onSendInput(env.command, true) },
            modifier = Modifier.padding(start = 12.dp)
        ) {
            Text("▶", style = MaterialTheme.typography.titleMedium)
        }
    }
}
