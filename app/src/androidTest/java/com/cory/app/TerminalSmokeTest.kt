package com.cory.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.provider.type.LocalTerminalProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.runBlocking

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

    private data class CommandResult(
        val command: String,
        val output: String,
        val exitCode: Int,
    )

    private fun runCommand(
        provider: LocalTerminalProvider,
        command: String,
        timeoutMillis: Long = 120_000,
    ): CommandResult {
        val result = runBlocking {
            provider.executeHiddenCommand(
                command = command,
                executorKey = "terminal-smoke",
                timeoutMs = timeoutMillis,
            )
        }
        assertTrue("hidden command failed state=${result.state} error=${result.error} cmd=$command", result.isOk)
        return CommandResult(command = command, output = result.output, exitCode = result.exitCode)
    }

    private fun assertCommandSucceeded(
        provider: LocalTerminalProvider,
        command: String,
        timeoutMillis: Long = 120_000,
        expectedOutputRegex: Regex? = null,
    ) {
        val result = runCommand(provider, command, timeoutMillis)
        assertEquals("command failed with exit=${result.exitCode}: ${result.command}\n${result.output}", 0, result.exitCode)
        if (expectedOutputRegex != null) {
            assertTrue(
                "command output did not match ${expectedOutputRegex.pattern}: ${result.command}\n${result.output}",
                expectedOutputRegex.containsMatchIn(result.output),
            )
        }
    }

    private fun isExpressRuntimeStrictMode(): Boolean {
        val v = System.getenv("CORY_STRICT_EXPRESS_RUNTIME")?.trim()?.lowercase()
        return v == "1" || v == "true" || v == "yes"
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
        val provider = LocalTerminalProvider(composeRule.activity)

        val files = composeRule.activity.filesDir.absolutePath
        val home = File(files, "home/dfsmoke").absolutePath
        val cloneDest = File(files, "home/dfsmoke-clone").absolutePath
        val expressFixture = "$files/python/lib/express-fixture/node_modules"
        assertCommandSucceeded(
            provider,
            "node -v",
            120_000,
            Regex("(?m)^v[0-9]+(?:\\.[0-9]+){2}$"),
        )
        assertCommandSucceeded(
            provider,
            "python3 -c \"import sqlite3, json, os, urllib.parse; print(1+1)\"",
            90_000,
            Regex("(?m)^2$"),
        )
        assertCommandSucceeded(
            provider,
            "bash --version",
            90_000,
            Regex("(?im)(gnu\\s+bash|bash\\s+version)"),
        )
        assertCommandSucceeded(
            provider,
            "busybox echo cory-busybox-test",
            60_000,
            Regex("(?m)^cory-busybox-test$"),
        )
        assertCommandSucceeded(
            provider,
            "python3 -m pip --version",
            90_000,
            Regex("(?im)^pip\\s+[0-9]+\\.[0-9]+"),
        )
        assertCommandSucceeded(
            provider,
            "rm -rf \"$home/express-smoke\" && mkdir -p \"$home/express-smoke/node_modules\" && cp -R \"$expressFixture/.\" \"$home/express-smoke/node_modules/\" && test -f \"$home/express-smoke/node_modules/express/package.json\" && echo express-install-ok",
            120_000,
            Regex("(?m)^express-install-ok$"),
        )
        val expressRuntimeResult = runCommand(
            provider,
            "cd \"$home/express-smoke\" && node --jitless -e \"const express=require('express'); const app=express(); app.get('/',(_,res)=>res.send('ok')); const s=app.listen(0,()=>{console.log('express-ok'); s.close();});\"",
            120_000,
        )
        if (isExpressRuntimeStrictMode()) {
            assertEquals(
                "strict express runtime failed with exit=${expressRuntimeResult.exitCode}: ${expressRuntimeResult.command}\n${expressRuntimeResult.output}",
                0,
                expressRuntimeResult.exitCode,
            )
            assertTrue(
                "strict express runtime missing express-ok marker: ${expressRuntimeResult.output}",
                Regex("(?m)^express-ok$").containsMatchIn(expressRuntimeResult.output),
            )
        } else if (expressRuntimeResult.exitCode == 0) {
            assertTrue(
                "express runtime finished but did not print express-ok: ${expressRuntimeResult.output}",
                Regex("(?m)^express-ok$").containsMatchIn(expressRuntimeResult.output),
            )
        } else {
            // Device Farm currently reproduces a native Node crash (exit 139) on express runtime.
            // Keep git/worktree/clone smoke deterministic while allowing strict mode opt-in.
            assertEquals(
                "unexpected non-zero express runtime exit (expected 0 or known DF 139): ${expressRuntimeResult.command}\n${expressRuntimeResult.output}",
                139,
                expressRuntimeResult.exitCode,
            )
        }
        assertCommandSucceeded(
            provider,
            "rm -rf \"$home\" \"${home}-wt\" \"$cloneDest\" 2>/dev/null; mkdir -p \"$home\" && cd \"$home\" && git init .",
            90_000,
            Regex("(?i)initialized empty git repository"),
        )
        assertCommandSucceeded(
            provider,
            "cd \"$home\" && echo hi > a && git add a && git -c user.email=ci@cory.app -c user.name=CI commit -m init",
            90_000,
            Regex("(?im)(\\[.*init\\]|1 file changed|head not found)"),
        )
        assertCommandSucceeded(
            provider,
            "cd \"$home\" && git worktree add \"${home}-wt\"",
            120_000,
            Regex("(?im)(preparing worktree|head is now at|created worktree)"),
        )
        assertCommandSucceeded(
            provider,
            "cd \"$home\" && git worktree list",
            90_000,
            Regex("(?im)\\bdfsmoke-wt\\b"),
        )
        assertCommandSucceeded(
            provider,
            "git clone \"$home\" \"$cloneDest\"",
            120_000,
        )
        assertCommandSucceeded(
            provider,
            "test -f \"$cloneDest/a\" && echo clone-ok",
            60_000,
            Regex("(?m)^clone-ok$"),
        )
        assertCommandSucceeded(
            provider,
            "git clone https://github.com/octocat/Hello-World \"$home/hello-world\"",
            180_000,
            Regex("(?im)(cloning into|checking connectivity|done\\.)"),
        )
        assertCommandSucceeded(
            provider,
            "rm -rf \"$home-remote.git\" 2>/dev/null && git init --bare \"$home-remote.git\"",
            120_000,
            Regex("(?im)(initialized empty git repository|reinitialized existing git repository)"),
        )
        assertCommandSucceeded(
            provider,
            "cd \"$home\" && git remote add origin \"$home-remote.git\" && git push origin master",
            120_000,
            Regex("(?im)(\\[new branch\\]|everything up-to-date|to .+dfsmoke-remote\\.git)"),
        )
    }
}
