package com.ai.assistance.operit.terminal

import android.content.Context
import android.util.Log
import java.io.File

/**
 * One-time setup for the native shell environment.
 *
 * Responsibilities (narrow and focused):
 *   1. Create the `usr/bin`, `usr/lib`, `home`, `tmp` directory layout
 *   2. Link `bash` and `busybox` from jniLibs (+ busybox applet symlinks)
 *   3. Write default `.bashrc` and `.profile`
 *   4. Record a bootstrap status file that the shell's .bashrc tails
 *      on login so users immediately see missing binaries
 *
 * Extension tools (python, node, rg, git, etc.) are wired by the host
 * app's runtime (see `CoryTerminalRuntime.kt`). This class only handles
 * the core shell.
 *
 * Call [ensureEnvironment] from a background thread at app startup.
 * It is idempotent — safe to call on every launch.
 */
object TerminalBootstrap {

    private const val TAG = "TerminalBootstrap"

    /** Standard busybox applets to symlink. */
    private val BUSYBOX_APPLETS = listOf(
        "awk", "basename", "cat", "chmod", "chown", "clear", "cp",
        "cut", "date", "df", "diff", "dirname", "du", "echo", "env",
        "expr", "false", "find", "free", "grep", "gzip", "head",
        "id", "kill", "ln", "ls", "md5sum", "mkdir", "mktemp",
        "more", "mv", "nc", "od", "patch", "ping", "printf",
        "ps", "pwd", "readlink", "realpath", "rm", "rmdir", "sed",
        "seq", "sh", "sha256sum", "sleep", "sort", "stat", "strings",
        "tail", "tar", "tee", "test", "time", "touch", "tr", "true",
        "tty", "uname", "uniq", "unzip", "uptime", "vi", "wc",
        "wget", "which", "whoami", "xargs", "yes"
    )

    fun ensureEnvironment(context: Context) {
        val filesDir = context.filesDir
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val prefixDir = File(filesDir, "usr")
        val binDir = File(prefixDir, "bin")
        val libDir = File(prefixDir, "lib")
        val homeDir = File(filesDir, "home")
        val tmpDir = File(filesDir, "tmp")

        // Create directory structure
        listOf(binDir, libDir, homeDir, tmpDir).forEach { it.mkdirs() }

        val errors = mutableListOf<String>()

        // ---- Core shell (from jniLibs, PIE-as-.so) ----
        val bashPath = linkNativeBinary(nativeLibDir, binDir, "libbash.so", "bash")
        if (bashPath == null) {
            val msg = "libbash.so missing from nativeLibDir=$nativeLibDir"
            Log.e(TAG, "CRITICAL: $msg")
            errors += msg
        }

        val busyboxPath = linkNativeBinary(nativeLibDir, binDir, "libbusybox.so", "busybox")
        if (busyboxPath == null) {
            val msg = "libbusybox.so missing from nativeLibDir=$nativeLibDir"
            Log.e(TAG, "CRITICAL: $msg")
            errors += msg
        } else {
            createBusyboxSymlinks(busyboxPath, binDir)
        }

        // ---- Shell config ----
        writeBashrc(homeDir, prefixDir, binDir)
        writeProfile(homeDir, binDir)

        // ---- Write bootstrap status so the user sees it on first shell ----
        writeBootstrapStatus(homeDir, errors)

        Log.d(TAG, "Environment ready: prefix=$prefixDir (errors=${errors.size})")
    }

    // ---- Internal helpers ----

    /**
     * Symlinks a PIE-as-.so binary from `nativeLibDir` (where Android's
     * installer placed it) into `binDir`.
     *
     * Returns the symlink path on success, null if the source doesn't
     * exist or the symlink couldn't be created.
     */
    private fun linkNativeBinary(
        nativeLibDir: String,
        binDir: File,
        soName: String,
        linkName: String
    ): String? {
        val source = File(nativeLibDir, soName)
        val target = File(binDir, linkName)

        if (!source.exists()) {
            return null
        }

        if (target.exists()) {
            try {
                if (target.canonicalPath == source.canonicalPath) {
                    return target.absolutePath
                }
            } catch (_: Exception) { }
            target.delete()
        }

        return try {
            Runtime.getRuntime().exec(arrayOf("ln", "-sf", source.absolutePath, target.absolutePath)).waitFor()
            if (target.exists()) target.absolutePath else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to symlink $soName -> $linkName", e)
            null
        }
    }

    private fun createBusyboxSymlinks(busyboxPath: String, binDir: File) {
        for (applet in BUSYBOX_APPLETS) {
            val link = File(binDir, applet)
            if (link.exists()) continue
            try {
                Runtime.getRuntime().exec(
                    arrayOf("ln", "-sf", busyboxPath, link.absolutePath)
                ).waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create busybox symlink: $applet", e)
            }
        }
    }

    /**
     * Writes a status file inside HOME that the shell's .bashrc tails on
     * interactive startup, so users see missing-binary warnings as soon
     * as they open a terminal instead of guessing why commands fail.
     */
    private fun writeBootstrapStatus(homeDir: File, errors: List<String>) {
        val statusFile = File(homeDir, ".cory-bootstrap-status")
        if (errors.isEmpty()) {
            if (statusFile.exists()) statusFile.delete()
            return
        }
        val banner = buildString {
            append("\u001B[1;31m[cory bootstrap]\u001B[0m critical setup errors:\n")
            for (e in errors) {
                append("  \u001B[31m✗\u001B[0m $e\n")
            }
            append("\n")
            append("\u001B[33mcommands like bash/python/node may not work until these are fixed.\u001B[0m\n")
            append("see app logcat (\u001B[36mTerminalBootstrap\u001B[0m) for details.\n")
        }
        statusFile.writeText(banner)
    }

    private fun writeBashrc(homeDir: File, prefixDir: File, binDir: File) {
        val bashrc = File(homeDir, ".bashrc")
        // Always rewrite — cheap, and avoids drift between app versions.
        bashrc.writeText(
            """
            # Cory shell environment
            export PATH="${binDir.absolutePath}:${'$'}PATH"
            export PREFIX="${prefixDir.absolutePath}"
            export HOME="${homeDir.absolutePath}"
            export PS1='\[\e[1;32m\]\u@cory\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]$ '
            alias ll='ls -lah --color=auto'
            alias la='ls -A --color=auto'
            alias l='ls --color=auto'
            alias python='python3'
            export CLICOLOR=1

            # Python
            export PYTHONDONTWRITEBYTECODE=1

            # Node
            export NODE_PATH="${prefixDir.absolutePath}/lib/node_modules"
            export npm_config_prefix="${prefixDir.absolutePath}"

            # Show bootstrap status banner on interactive shell startup
            if [ -f "${'$'}HOME/.cory-bootstrap-status" ] && [ -n "${'$'}PS1" ]; then
                cat "${'$'}HOME/.cory-bootstrap-status"
            fi
            """.trimIndent() + "\n"
        )
    }

    private fun writeProfile(homeDir: File, binDir: File) {
        val profile = File(homeDir, ".profile")
        // Always rewrite to stay in sync with .bashrc
        profile.writeText(
            """
            # Source .bashrc for interactive login shells
            if [ -f "${'$'}HOME/.bashrc" ]; then
                . "${'$'}HOME/.bashrc"
            fi
            """.trimIndent() + "\n"
        )
    }
}
