package com.ai.assistance.operit.terminal.provider.type

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.terminal.Pty
import com.ai.assistance.operit.terminal.TerminalBootstrap
import com.ai.assistance.operit.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Local terminal provider.
 *
 * Launches a bash shell directly via PTY.
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
    private val pythonHomeDir: File = File(filesDir, "python")
    private val pythonStdlibDir: File = File(pythonHomeDir, "lib/python3.14")
    private val pythonDynloadDir: File = File(pythonStdlibDir, "lib-dynload")
    private val pythonSitePackagesDir: File = File(pythonStdlibDir, "site-packages")
    private val pythonEnsurepipBundledDir: File = File(pythonStdlibDir, "ensurepip/_bundled")
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
                // #region agent log
                appendDebugLog(
                    hypothesisId = "A",
                    location = "LocalTerminalProvider.kt:executeHiddenCommand:entry",
                    message = "Hidden command execution started",
                    data = mapOf(
                        "executorKey" to executorKey,
                        "timeoutMs" to timeoutMs,
                        "commandLength" to command.length,
                        "containsNpmCli" to command.contains("npm-cli.js"),
                    ),
                )
                // #endregion

                val env = buildEnvironment()
                val pb = ProcessBuilder(
                    File(binDir, "bash").absolutePath,
                    "-c",
                    command
                )
                pb.directory(homeDir)
                pb.redirectErrorStream(true)
                pb.environment().clear()
                pb.environment().putAll(env)

                // #region agent log
                appendDebugLog(
                    hypothesisId = "B",
                    location = "LocalTerminalProvider.kt:executeHiddenCommand:env",
                    message = "Hidden command environment snapshot",
                    data = mapOf(
                        "PATH" to env["PATH"],
                        "LD_LIBRARY_PATH" to env["LD_LIBRARY_PATH"],
                        "HOME" to env["HOME"],
                        "TMPDIR" to env["TMPDIR"],
                    ),
                )
                // #endregion

                if (command.contains("npm-cli.js")) {
                    val nodePath = File(binDir, "node")
                    val npmCliPathRegex = Regex("node\\s+\"([^\"]*npm-cli\\.js)\"")
                    val npmCliPath = npmCliPathRegex.find(command)?.groupValues?.getOrNull(1)
                    val npmCliFile = npmCliPath?.let { File(it) }
                    // #region agent log
                    appendDebugLog(
                        hypothesisId = "C",
                        location = "LocalTerminalProvider.kt:executeHiddenCommand:npmPreflight",
                        message = "npm command preflight paths",
                        data = mapOf(
                            "nodeExists" to nodePath.exists(),
                            "nodeCanonicalPath" to nodePath.safeCanonicalPath(),
                            "nodeCanExecute" to nodePath.canExecute(),
                            "npmCliPath" to npmCliPath,
                            "npmCliExists" to (npmCliFile?.exists() ?: false),
                            "npmCliSize" to (npmCliFile?.length() ?: -1L),
                        ),
                    )
                    // #endregion
                }

                val proc = pb.start()
                val output = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val exitCode = proc.waitFor()

                // #region agent log
                appendDebugLog(
                    hypothesisId = "D",
                    location = "LocalTerminalProvider.kt:executeHiddenCommand:exit",
                    message = "Hidden command finished",
                    data = mapOf(
                        "exitCode" to exitCode,
                        "outputLength" to output.length,
                        "outputTail" to output.takeLast(600),
                    ),
                )
                // #endregion

                if (exitCode == 139 && command.contains("npm-cli.js")) {
                    val diag = ProcessBuilder(
                        File(binDir, "bash").absolutePath,
                        "-c",
                        "command -v node; ls -l \"${File(binDir, "node").absolutePath}\"; node -p \"JSON.stringify(process.versions)\"; node -p \"process.execPath\"",
                    )
                    diag.directory(homeDir)
                    diag.redirectErrorStream(true)
                    diag.environment().clear()
                    diag.environment().putAll(env)
                    val diagProc = diag.start()
                    val diagOutput = diagProc.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    val diagExit = diagProc.waitFor()
                    // #region agent log
                    appendDebugLog(
                        hypothesisId = "E",
                        location = "LocalTerminalProvider.kt:executeHiddenCommand:nodeDiag",
                        message = "Node diagnostic after npm exit 139",
                        data = mapOf(
                            "diagExit" to diagExit,
                            "diagOutputTail" to diagOutput.takeLast(800),
                        ),
                    )
                    // #endregion
                }

                HiddenExecResult(
                    output = output.trimEnd(),
                    exitCode = exitCode
                )
            } catch (e: Exception) {
                // #region agent log
                appendDebugLog(
                    hypothesisId = "A",
                    location = "LocalTerminalProvider.kt:executeHiddenCommand:exception",
                    message = "Hidden command threw exception",
                    data = mapOf(
                        "exceptionType" to e.javaClass.name,
                        "exceptionMessage" to (e.message ?: ""),
                    ),
                )
                // #endregion
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

    private fun buildEnvironment(): Map<String, String> {
        val pipWheel = pythonEnsurepipBundledDir.listFiles()
            ?.firstOrNull { file ->
                file.isFile &&
                    file.name.startsWith("pip-") &&
                    file.name.endsWith(".whl")
            }
        val pythonPathEntries = mutableListOf(
            pythonStdlibDir.absolutePath,
            pythonDynloadDir.absolutePath,
            pythonSitePackagesDir.absolutePath,
        )
        if (pipWheel != null) {
            pythonPathEntries += pipWheel.absolutePath
        }
        return mapOf(
            "HOME" to homeDir.absolutePath,
            "PATH" to "${binDir.absolutePath}:${nativeLibDir}:/system/bin",
            "PREFIX" to prefixDir.absolutePath,
            "LD_LIBRARY_PATH" to "${libDir.absolutePath}:${nativeLibDir}",
            "PYTHONHOME" to pythonHomeDir.absolutePath,
            "PYTHONPATH" to pythonPathEntries.joinToString(":"),
            "TMPDIR" to tmpDir.absolutePath,
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            "COLORTERM" to "truecolor",
            "SHELL" to File(binDir, "bash").absolutePath,
        )
    }

    private fun appendDebugLog(
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        try {
            val payload = JSONObject()
                .put("hypothesisId", hypothesisId)
                .put("location", location)
                .put("message", message)
                .put("data", JSONObject(data))
                .put("timestamp", System.currentTimeMillis())
            File("/opt/cursor/logs/debug.log").appendText(payload.toString() + "\n")
        } catch (_: Exception) {
        }
    }

    private fun File.safeCanonicalPath(): String = try {
        canonicalPath
    } catch (_: Exception) {
        absolutePath
    }
}
