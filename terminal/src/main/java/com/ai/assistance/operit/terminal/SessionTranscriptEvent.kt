package com.ai.assistance.operit.terminal

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class UserInputEvent(
    val sessionId: String,
    val text: String,
    val isCommand: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
) : Parcelable

@Parcelize
data class SessionOutputEvent(
    val sessionId: String,
    val chunk: String,
    val timestampMs: Long = System.currentTimeMillis()
) : Parcelable
