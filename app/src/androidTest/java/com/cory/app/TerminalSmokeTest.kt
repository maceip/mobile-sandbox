package com.cory.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.assistance.operit.terminal.TerminalManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device Farm / CI: exercises the real PTY + Canvas terminal + bundled toolchain
 * (node, python3, git, bash, busybox, pip, npm) inside the app sandbox.
 *
 * Replaces the former run-as + inner-smoke.sh flow; no debuggable APK required.
 */
@RunWith(AndroidJUnit4::class)
class TerminalSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComposeSandboxActivity>()

    private fun terminalTextPlain(manager: TerminalManager): String =
        manager.shadowEmulator.value.renderFullTerminalTextForSemantics(maxChars = 80_000)

    private fun waitUntil(timeoutMillis: Long, pollMillis: Long = 100, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            if (condition()) return
            Thread.sleep(pollMillis)
        }
        throw AssertionError("condition not met within ${timeoutMillis}ms")
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        else -> "\"${jsonEscape(value.toString())}\""
    }

    private fun debugLog(
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        val dataJson = data.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"${jsonEscape(k)}\":${jsonValue(v)}"
        }
        val line = "{\"hypothesisId\":\"${jsonEscape(hypothesisId)}\"," +
            "\"location\":\"${jsonEscape(location)}\"," +
            "\"message\":\"${jsonEscape(message)}\"," +
            "\"data\":$dataJson," +
            "\"timestamp\":${System.currentTimeMillis()}}"
        File("/opt/cursor/logs/debug.log").appendText("$line\n")
    }

    private data class CommandResult(
        val command: String,
        val output: String,
        val exitCode: Int,
    )

    private fun runCommand(
        manager: TerminalManager,
        command: String,
        timeoutMillis: Long = 120_000,
    ): CommandResult {
        val token = "${System.currentTimeMillis()}_${System.nanoTime()}"
        val startMarker = "__DF_START_${token}__"
        val rcMarker = "__DF_RC_${token}__:"
        val endMarker = "__DF_END_${token}__"

        val wrapped = "printf '$startMarker\\n'; $command; __df_rc=$?; printf '$rcMarker%s\\n' \"\$__df_rc\"; printf '$endMarker\\n'"
        // #region agent log
        debugLog(
            hypothesisId = "A",
            location = "TerminalSmokeTest.kt:runCommand:entry",
            message = "Submitting command to PTY via sendCommand",
            data = mapOf("command" to command, "timeoutMillis" to timeoutMillis, "token" to token),
        )
        // #endregion
        val sent = runBlocking { manager.sendCommand(wrapped) }
        // #region agent log
        debugLog(
            hypothesisId = "A",
            location = "TerminalSmokeTest.kt:runCommand:afterSend",
            message = "sendCommand returned",
            data = mapOf("command" to command, "sent" to sent),
        )
        // #endregion
        assertTrue("failed to write command to PTY: $command", sent)

        waitUntil(timeoutMillis) {
            terminalTextPlain(manager).contains(endMarker)
        }

        val snapshot = terminalTextPlain(manager)
        // #region agent log
        debugLog(
            hypothesisId = "B",
            location = "TerminalSmokeTest.kt:runCommand:markerSeen",
            message = "End marker observed in terminal snapshot",
            data = mapOf("command" to command, "snapshotLength" to snapshot.length),
        )
        // #endregion
        val start = snapshot.lastIndexOf(startMarker)
        val end = if (start >= 0) snapshot.indexOf(endMarker, start) else -1
        assertTrue("missing output markers for command: $command", start >= 0 && end >= 0)
        val section = snapshot.substring(start, end + endMarker.length)
        val rcMatch = Regex("${Regex.escape(rcMarker)}(\\d+)").find(section)
            ?: throw AssertionError("missing exit marker for command: $command\n$section")
        val exitCode = rcMatch.groupValues[1].toInt()
        // #region agent log
        debugLog(
            hypothesisId = "C",
            location = "TerminalSmokeTest.kt:runCommand:exitCode",
            message = "Command finished with explicit exit marker",
            data = mapOf("command" to command, "exitCode" to exitCode),
        )
        // #endregion
        return CommandResult(command = command, output = section, exitCode = exitCode)
    }

    private fun assertCommandSucceeded(
        manager: TerminalManager,
        command: String,
        timeoutMillis: Long = 120_000,
        expectedOutputRegex: Regex? = null,
    ) {
        val result = runCommand(manager, command, timeoutMillis)
        assertEquals("command failed with exit=${result.exitCode}: ${result.command}\n${result.output}", 0, result.exitCode)
        if (expectedOutputRegex != null) {
            assertTrue(
                "command output did not match ${expectedOutputRegex.pattern}: ${result.command}\n${result.output}",
                expectedOutputRegex.containsMatchIn(result.output),
            )
        }
    }

    @Test
    fun bundledToolchainAndGitWorktreeEndToEnd() {
        waitUntil(120_000) {
            composeRule.onAllNodesWithTag("compose-sandbox-terminal-ready").fetchSemanticsNodes()
                .isNotEmpty()
        }
        val manager = TerminalManager.getInstance(composeRule.activity)
        waitUntil(120_000) {
            manager.sessions.value.isNotEmpty()
        }
        // Until the PTY has emitted output, semantics are blank spaces only; sendRawInput is also
        // a no-op if the first session never attached. Wait for non-trivial visible text.
        waitUntil(120_000) {
            terminalTextPlain(manager).count { !it.isWhitespace() } >= 8
        }
        // #region agent log
        debugLog(
            hypothesisId = "D",
            location = "TerminalSmokeTest.kt:bundledToolchainAndGitWorktreeEndToEnd:ready",
            message = "Terminal session ready; beginning deterministic command flow",
            data = mapOf(
                "sessionCount" to manager.sessions.value.size,
                "nonWhitespaceChars" to terminalTextPlain(manager).count { !it.isWhitespace() },
            ),
        )
        // #endregion

        val files = composeRule.activity.filesDir.absolutePath
        val home = File(files, "home/dfsmoke").absolutePath
        val cloneDest = File(files, "home/dfsmoke-clone").absolutePath

        assertCommandSucceeded(
            manager,
            "node -v",
            120_000,
            Regex("(?m)^v[0-9]+(?:\\.[0-9]+){2}$"),
        )
        assertCommandSucceeded(
            manager,
            "python3 -c \"import sqlite3, json, os, urllib.parse; print(1+1)\"",
            90_000,
            Regex("(?m)^2$"),
        )
        assertCommandSucceeded(
            manager,
            "bash --version",
            90_000,
            Regex("(?im)(gnu\\s+bash|bash\\s+version)"),
        )
        assertCommandSucceeded(
            manager,
            "busybox echo cory-busybox-test",
            60_000,
            Regex("(?m)^cory-busybox-test$"),
        )
        assertCommandSucceeded(
            manager,
            "python3 -m pip --version",
            90_000,
            Regex("(?im)^pip\\s+[0-9]+\\.[0-9]+"),
        )
        assertCommandSucceeded(
            manager,
            "node \"$files/python/lib/node_modules/npm/bin/npm-cli.js\" --version",
            90_000,
            Regex("(?m)^[0-9]+\\.[0-9]+\\.[0-9]+$"),
        )

        assertCommandSucceeded(
            manager,
            "rm -rf \"$home\" \"${home}-wt\" \"$cloneDest\" 2>/dev/null; mkdir -p \"$home\" && cd \"$home\" && git init .",
            90_000,
            Regex("(?i)initialized empty git repository"),
        )
        assertCommandSucceeded(
            manager,
            "cd \"$home\" && echo hi > a && git add a && git -c user.email=ci@cory.app -c user.name=CI commit -m init",
            90_000,
            Regex("(?im)(\\[.*init\\]|1 file changed)"),
        )
        assertCommandSucceeded(
            manager,
            "cd \"$home\" && git worktree add \"${home}-wt\"",
            120_000,
            Regex("(?im)(preparing worktree|head is now at)"),
        )
        assertCommandSucceeded(
            manager,
            "cd \"$home\" && git worktree list",
            90_000,
            Regex(Regex.escape("${home}-wt")),
        )
        assertCommandSucceeded(
            manager,
            "git clone \"$home\" \"$cloneDest\"",
            120_000,
        )
        assertCommandSucceeded(
            manager,
            "test -f \"$cloneDest/a\" && echo clone-ok",
            60_000,
            Regex("(?m)^clone-ok$"),
        )
    }
}
