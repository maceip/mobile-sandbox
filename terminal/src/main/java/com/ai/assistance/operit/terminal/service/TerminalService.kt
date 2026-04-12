package com.ai.assistance.operit.terminal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import androidx.annotation.RequiresApi
import com.ai.assistance.operit.terminal.ITerminalCallback
import com.ai.assistance.operit.terminal.ITerminalService
import com.ai.assistance.operit.terminal.CommandExecutionEvent
import com.ai.assistance.operit.terminal.SessionDirectoryEvent
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.data.SessionInitState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class TerminalService : Service() {
    companion object {
        private const val CHANNEL_ID = "terminal-runtime"
        private const val NOTIFICATION_ID = 4102
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private lateinit var terminalManager: TerminalManager
    private val callbacks = RemoteCallbackList<ITerminalCallback>()

    private val binder = object : ITerminalService.Stub() {
        override fun createSession(): String {
            // runBlocking suspend
            return runBlocking {
                try {
                    val newSession = terminalManager.createNewSession()
                    newSession.id
                } catch (e: Exception) {
                    Log.e("TerminalService", "Session creation failed", e)
                    throw e
                }
            }
        }

        override fun switchToSession(sessionId: String) {
            terminalManager.switchToSession(sessionId)
        }

        override fun closeSession(sessionId: String) {
            terminalManager.closeSession(sessionId)
        }

        override fun sendCommand(command: String): String {
            return runBlocking {
                val sent = terminalManager.sendCommand(command)
                if (sent) command else ""
            }
        }

        override fun sendInterruptSignal() {
            terminalManager.sendInterruptSignal()
        }

        override fun registerCallback(callback: ITerminalCallback?) {
            callback?.let { callbacks.register(it) }
        }

        override fun unregisterCallback(callback: ITerminalCallback?) {
            callback?.let { callbacks.unregister(it) }
        }

        override fun requestStateUpdate() {
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        terminalManager = TerminalManager.getInstance(applicationContext)
        
        terminalManager.commandExecutionEvents
            .onEach { event ->
                Log.d("TerminalService", "Received command execution event: $event")
                broadcastCommandExecutionEvent(event)
            }
            .launchIn(scope)
            
        terminalManager.directoryChangeEvents
            .onEach { event ->
                Log.d("TerminalService", "Received directory change event: $event")
                broadcastDirectoryChangeEvent(event)
            }
            .launchIn(scope)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        callbacks.kill()
    }
    
    private fun broadcastCommandExecutionEvent(event: CommandExecutionEvent) {
        val n = callbacks.beginBroadcast()
        Log.d("TerminalService", "Broadcasting command execution event to $n callbacks: $event")
        for (i in 0 until n) {
            try {
                callbacks.getBroadcastItem(i).onCommandExecutionUpdate(event)
                Log.d("TerminalService", "Successfully sent command execution event to callback $i")
            } catch (e: Exception) {
                Log.e("TerminalService", "Error broadcasting command execution event to callback $i", e)
            }
        }
        callbacks.finishBroadcast()
        Log.d("TerminalService", "Finished broadcasting command execution event")
    }
    
    private fun broadcastDirectoryChangeEvent(event: SessionDirectoryEvent) {
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            try {
                callbacks.getBroadcastItem(i).onSessionDirectoryChanged(event)
            } catch (e: Exception) {
                Log.e("TerminalService", "Error broadcasting directory change event", e)
            }
        }
        callbacks.finishBroadcast()
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Terminal runtime",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Cory terminal running")
            .setContentText("Keeping agent and shell sessions alive")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }
}
