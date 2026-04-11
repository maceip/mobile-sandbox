package com.ai.assistance.operit.terminal.provider.type

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.terminal.Pty
import com.ai.assistance.operit.terminal.TerminalBootstrap
import com.ai.assistance.operit.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Local terminal provider.
 *
 * Launches a bash shell directly via PTY — no proot, no Ubuntu rootfs.
 * Tools (python, node, rg, git, etc.) are expected in PREFIX/bin,
 * set up by [TerminalBootstrap].
 */
class LocalTerminalProvider(
    private val context: Context
) : TerminalProvider {

    private val filesDir: File = context.filesDir
    private val prefixDir: File = File(filesDir, "usr")
    private val binDir: File = File(prefixDir, "bin")
    private val libDir: File = File(prefixDir, "lib")
    private val homeDir: File = File(filesDir, "home")
    private val tmpDir: File = File(filesDir, "tmp")
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir

    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()

    companion object {
        private const val TAG = "LocalTerminalProvider"
    }

    override suspend fun isConnected(): Boolean = true

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() {
        activeSessions.keys.toList().forEach { closeSession(it) }
    }

    override suspend fun startSession(sessionId: String): Result<Pair<TerminalSession, Pty>> {
        return withContext(Dispatchers.IO) {
            try {
                val command = arrayOf(
                    File(binDir, "bash").absolutePath,
                    "--login"
                )
                val env = buildEnvironment()

                Log.d(TAG, "Starting shell: ${command.joinToString(" ")}")

                val pty = Pty.start(command, env, homeDir)

                val session = TerminalSession(
                    process = pty.process,
                    stdout = pty.stdout,
                    stdin = pty.stdin
                )

                activeSessions[sessionId] = session
                Result.success(Pair(session, pty))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start local terminal session", e)
                Result.failure(e)
            }
        }
    }

    override suspend fun closeSession(sessionId: String) {
        activeSessions.remove(sessionId)?.let { session ->
            session.process.destroy()
            Log.d(TAG, "Closed session: $sessionId")
        }
    }

    override suspend fun executeHiddenCommand(
        command: String,
        executorKey: String,
        timeoutMs: Long
    ): HiddenExecResult {
        // Simple implementation: run via a short-lived shell
        return withContext(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder(
                    File(binDir, "bash").absolutePath,
                    "-c",
                    command
                )
                pb.directory(homeDir)
                pb.redirectErrorStream(true)
                pb.environment().clear()
                pb.environment().putAll(buildEnvironment())

                val proc = pb.start()
                val output = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val exitCode = proc.waitFor()

                HiddenExecResult(
                    output = output.trimEnd(),
                    exitCode = exitCode
                )
            } catch (e: Exception) {
                Log.e(TAG, "Hidden command failed", e)
                HiddenExecResult(
                    output = "",
                    exitCode = -1,
                    state = HiddenExecResult.State.EXECUTION_ERROR,
                    error = e.message ?: "Hidden command execution failed"
                )
            }
        }
    }

    override suspend fun getWorkingDirectory(): String = homeDir.absolutePath

    override fun getEnvironment(): Map<String, String> = buildEnvironment()

    private fun buildEnvironment(): Map<String, String> = mapOf(
        "HOME" to homeDir.absolutePath,
        "PATH" to "${binDir.absolutePath}:${nativeLibDir}:/system/bin",
        "PREFIX" to prefixDir.absolutePath,
        "LD_LIBRARY_PATH" to "${libDir.absolutePath}:${nativeLibDir}",
        "TMPDIR" to tmpDir.absolutePath,
        "TERM" to "xterm-256color",
        "LANG" to "en_US.UTF-8",
        "COLORTERM" to "truecolor",
        "SHELL" to File(binDir, "bash").absolutePath
    )
}
