package com.ai.assistance.operit.terminal

import android.util.Log
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.data.TerminalState
import com.ai.assistance.operit.terminal.provider.type.TerminalType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SessionManager(private val terminalManager: TerminalManager) {
    
    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state.asStateFlow()
    
    /**
     *
     * 
     * @param title 
     * @param terminalType 
     */
    fun createNewSession(
        title: String? = null,
        terminalType: TerminalType
    ): TerminalSessionData {
        lateinit var newSession: TerminalSessionData
        _state.update { currentState ->
            val sessionCount = currentState.sessions.size + 1
            val defaultTitle = when (terminalType) {
                TerminalType.LOCAL -> "Shell $sessionCount"
                TerminalType.SSH -> "SSH $sessionCount"
                else -> "Terminal $sessionCount"
            }
            newSession = TerminalSessionData(
                title = title ?: defaultTitle,
                terminalType = terminalType
            )
            currentState.copy(
                sessions = currentState.sessions + newSession,
                currentSessionId = newSession.id
            )
        }
        
        Log.d("SessionManager", "Created new session: ${newSession.id} (type: $terminalType)")
        return newSession
    }
    
    fun switchToSession(sessionId: String): Boolean {
        var switched = false
        _state.update { currentState ->
            if (currentState.sessions.any { it.id == sessionId }) {
                switched = true
                currentState.copy(currentSessionId = sessionId)
            } else {
                currentState
            }
        }
        if (switched) {
            Log.d("SessionManager", "Switched to session: $sessionId")
        } else {
            Log.w("SessionManager", "Session not found: $sessionId")
        }
        return switched
    }
    
    fun closeSession(sessionId: String) {
        _state.update { currentState ->
            val sessionToClose = currentState.sessions.find { it.id == sessionId }
            
            sessionToClose?.let { session ->
                try {
                    session.readJob?.cancel()
                    session.sessionWriter?.close()
                    terminalManager.closeTerminalSession(session.id)
                } catch (e: Exception) {
                    Log.e("SessionManager", "Error cleaning up session", e)
                }
            }
            
            val updatedSessions = currentState.sessions.filter { it.id != sessionId }
            val newCurrentSessionId = if (currentState.currentSessionId == sessionId) {
                updatedSessions.firstOrNull()?.id
            } else {
                currentState.currentSessionId
            }
            
            currentState.copy(
                sessions = updatedSessions,
                currentSessionId = newCurrentSessionId
            )
        }

        terminalManager.onSessionClosed(sessionId)
        
        Log.d("SessionManager", "Closed session: $sessionId")
    }
    
    /**
     * Insert a fully-initialized session into state.
     * Used by TerminalManager after the provider has successfully started.
     */
    fun insertSession(session: TerminalSessionData) {
        _state.update { currentState ->
            currentState.copy(
                sessions = currentState.sessions + session,
                currentSessionId = session.id
            )
        }
        Log.d("SessionManager", "Inserted session: ${session.id}")
    }

    fun updateSession(sessionId: String, updater: (TerminalSessionData) -> TerminalSessionData) {
        _state.update { currentState ->
            val updatedSessions = currentState.sessions.map { session ->
                if (session.id == sessionId) {
                    updater(session)
                } else {
                    session
                }
            }
            currentState.copy(sessions = updatedSessions)
        }
    }
    
    fun saveScrollOffset(sessionId: String, scrollOffset: Float) {
        updateSession(sessionId) { session ->
            session.copy(scrollOffsetY = scrollOffset)
        }
    }
    
    fun getScrollOffset(sessionId: String): Float {
        return getSession(sessionId)?.scrollOffsetY ?: 0f
    }
    
    fun getCurrentSession(): TerminalSessionData? {
        return _state.value.currentSession
    }
    
    fun getSession(sessionId: String): TerminalSessionData? {
        return _state.value.sessions.find { it.id == sessionId }
    }
    
         /*
    private fun cleanupSession(session: TerminalSessionData) {
        // 
        session.readJob?.cancel()
        
        // 
        session.sessionWriter?.close()
        session.terminalSession?.process?.destroy()
    }
    */
    
    fun cleanup() {
        val currentState = _state.value
        currentState.sessions.forEach { session ->
            try {
                closeSession(session.id)
            } catch (e: Exception) {
                Log.e("SessionManager", "Error cleaning up session ${session.id}", e)
            }
        }
        
        _state.value = TerminalState()
        Log.d("SessionManager", "All sessions cleaned up")
    }
} 
