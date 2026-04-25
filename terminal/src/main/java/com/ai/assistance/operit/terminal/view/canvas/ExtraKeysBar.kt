package com.ai.assistance.operit.terminal.view.canvas

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Terminal convenience controls inspired by maceip/v9's D-pad and keyboard rail.
 *
 * Renders a row of special keys (ESC, CTRL, TAB, arrows, PASTE) that float
 * above the soft keyboard. On phones this replaces the need for a physical
 * keyboard to send escape sequences, control characters, and arrow navigation.
 */
@Composable
fun ExtraKeysBar(
    visible: Boolean,
    onInput: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var ctrlActive by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A28))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExtraKey("ESC", modifier = Modifier.weight(1f)) {
                ctrlActive = false
                onInput("\u001b")
            }

            ExtraKey(
                label = "CTRL",
                isActive = ctrlActive,
                modifier = Modifier.weight(1f)
            ) {
                ctrlActive = !ctrlActive
            }

            ExtraKey("TAB", modifier = Modifier.weight(1f)) {
                if (ctrlActive) {
                    ctrlActive = false
                    onInput("\u0009")
                } else {
                    onInput("\t")
                }
            }

            ExtraKey("\u2190", modifier = Modifier.weight(0.8f)) {
                if (ctrlActive) {
                    ctrlActive = false
                    onInput("\u001b[1;5D")
                } else {
                    onInput("\u001b[D")
                }
            }

            ExtraKey("\u2191", modifier = Modifier.weight(0.8f)) {
                if (ctrlActive) {
                    ctrlActive = false
                    onInput("\u001b[1;5A")
                } else {
                    onInput("\u001b[A")
                }
            }

            ExtraKey("\u2193", modifier = Modifier.weight(0.8f)) {
                if (ctrlActive) {
                    ctrlActive = false
                    onInput("\u001b[1;5B")
                } else {
                    onInput("\u001b[B")
                }
            }

            ExtraKey("\u2192", modifier = Modifier.weight(0.8f)) {
                if (ctrlActive) {
                    ctrlActive = false
                    onInput("\u001b[1;5C")
                } else {
                    onInput("\u001b[C")
                }
            }

            val context = LocalContext.current
            ExtraKey("PASTE", modifier = Modifier.weight(1.2f)) {
                ctrlActive = false
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = clipboard?.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.text
                    ?.toString()
                if (!text.isNullOrEmpty()) {
                    onInput(text)
                }
            }
        }
    }
}

@Composable
private fun ExtraKey(
    label: String,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    val bgColor = if (isActive) Color(0xFF3D5A00) else Color(0xFF2D2D3A)
    val textColor = if (isActive) Color(0xFF9BFF00) else Color(0xFFB0B0C0)

    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
