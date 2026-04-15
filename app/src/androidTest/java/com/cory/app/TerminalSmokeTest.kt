package com.cory.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.typeTextIntoFocusedView
import androidx.test.espresso.matcher.ViewMatchers.withClassName
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.assistance.operit.terminal.TerminalManager
import org.hamcrest.Matchers.endsWith
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

    private fun terminalText(): String {
        val nodes =
            composeRule.onAllNodesWithTag("terminal-output", useUnmergedTree = true)
                .fetchSemanticsNodes()
        val best = nodes.maxByOrNull { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString("") { seg -> seg.toString() }
                ?.length ?: 0
        }
        return best?.config?.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString("") { seg -> seg.toString() }
            ?: ""
    }

    /** Semantics mirror raw screen cells; strip CSI/OSC so matchers see visible text (e.g. node -v). */
    private fun terminalTextPlain(): String =
        terminalText()
            .replace(Regex("\u001b\\[[0-9;:]*[ -/]*[@-~]"), "")
            .replace(Regex("\u001b\\][^\u0007]*\u0007"), "")
            .replace(Regex("\u001b."), "")

    private fun waitUntil(timeoutMillis: Long, pollMillis: Long = 100, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            if (condition()) return
            Thread.sleep(pollMillis)
        }
        throw AssertionError("condition not met within ${timeoutMillis}ms")
    }

    private fun waitForSubstring(
        needle: String,
        timeoutMillis: Long = 120_000,
        ignoreCase: Boolean = true,
    ) {
        waitUntil(timeoutMillis) {
            val t = terminalText()
            if (ignoreCase) t.contains(needle, ignoreCase = true)
            else t.contains(needle)
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
            terminalTextPlain().count { !it.isWhitespace() } >= 8
        }

        val files = composeRule.activity.filesDir.absolutePath
        val home = File(files, "home/dfsmoke").absolutePath
        val cloneDest = File(files, "home/dfsmoke-clone").absolutePath

        onView(withClassName(endsWith("CanvasTerminalView"))).perform(click())
        Thread.sleep(500)

        fun typeLine(line: String) {
            onView(withClassName(endsWith("CanvasTerminalView"))).perform(
                typeTextIntoFocusedView(line + "\n"),
            )
            Thread.sleep(400)
        }

        typeLine("node -v")
        waitUntil(120_000) {
            val t = terminalTextPlain()
            Regex("[vV][0-9]+(?:\\.[0-9]+)+").containsMatchIn(t) ||
                (t.contains("node", ignoreCase = true) &&
                    Regex("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?").containsMatchIn(t))
        }

        typeLine(
            "python3 -c \"import ssl, sqlite3, json, os, urllib.request; print(1+1)\"",
        )
        waitForSubstring("2", 60_000)

        typeLine("bash --version")
        waitForSubstring("bash", 60_000)

        typeLine("busybox echo cory-busybox-test")
        waitForSubstring("cory-busybox-test", 60_000)

        typeLine("pip --version")
        waitForSubstring("pip", 90_000)

        typeLine("npm --version")
        waitUntil(90_000) {
            Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$", RegexOption.MULTILINE).containsMatchIn(terminalTextPlain())
        }

        typeLine("rm -rf \"$home\" \"$cloneDest\" 2>/dev/null; mkdir -p \"$home\" && cd \"$home\" && git init .")
        waitForSubstring("Initialized", 90_000)

        typeLine("echo hi > a && git add a")
        typeLine("git -c user.email=ci@cory.app -c user.name=CI commit -m init")
        waitForSubstring("init", 90_000)

        typeLine("git worktree list")
        waitForSubstring(home, 90_000)
        waitForSubstring("[", 90_000)

        typeLine("git worktree add \"${home}-wt\"")
        waitForSubstring("Created", 120_000)

        typeLine("git worktree list")
        waitForSubstring("${home}-wt", 120_000)

        typeLine("git clone https://github.com/octocat/Hello-World \"$cloneDest\"")
        waitForSubstring("Hello-World", 180_000)
    }
}
