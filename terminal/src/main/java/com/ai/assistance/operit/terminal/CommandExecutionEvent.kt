package com.ai.assistance.operit.terminal

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CommandExecutionEvent(
    val commandId: String,
    val command: String,
    val sessionId: String,
    val outputChunk: String,
    val isCompleted: Boolean,
    val exitCode: Int? = null,
    val durationMs: Long? = null
) : Parcelable

@Parcelize
data class SessionDirectoryEvent(
    val sessionId: String,
    val currentDirectory: String
) : Parcelable 
