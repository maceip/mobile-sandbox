package com.example.orderfiledemo.compose

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.main.TerminalScreen
import com.ai.assistance.operit.terminal.rememberTerminalEnv
import com.ai.assistance.operit.terminal.service.TerminalService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ComposeSandboxActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the foreground service to keep PTY sessions alive
        // when the app is backgrounded.
        val serviceIntent = Intent(this, TerminalService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            ComposeSandboxScreen()
        }
    }
}

@Composable
private fun ComposeSandboxScreen() {
    val context = LocalContext.current
    var startupError by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        startupError = null
        ready = false
        try {
            withContext(Dispatchers.IO) {
                CoryTerminalRuntime.ensureReady(context)
            }
            ready = true
        } catch (t: Throwable) {
            startupError = t.message ?: t.javaClass.simpleName
        }
    }

    Surface(color = Color(0xFF11161C)) {
        when {
            ready -> {
                val manager = remember { TerminalManager.getInstance(context) }
                val env = rememberTerminalEnv(manager)
                TerminalScreen(env)
            }
            startupError != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "Terminal bootstrap failed: $startupError", color = Color(0xFFFFB4AB))
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
