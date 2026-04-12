package com.ai.assistance.operit.terminal

import android.content.Context
import android.util.Log
import java.io.File

/**
 * One-time setup for the native shell environment.
 *
 * Creates the directory layout, symlinks bash/busybox from the native
 * library directory into PREFIX/bin, links tool binaries that the host
 * app extracts to filesDir (python, node, rg, etc.), bootstraps npm
 * and pip, and writes a default .bashrc.
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

    /**
     * Tool binaries extracted by the host app to filesDir/python/bin/
     * (via assets). Fallback path — used only if the native lib version
     * is missing.
     */
    private val ASSET_TOOL_BINARIES = listOf(
        "node",
        "rg",
        "busybox"
    )

    /**
     * Native binaries packaged as lib*.so in jniLibs (extracted to
     * nativeLibDir). These are PIE executables disguised as shared libs
     * so Android unpacks them during install. Map: soName -> binName.
     *
     * Sourced from S3 via the host app's fetchNativeDeps task.
     */
    private val NATIVE_TOOL_BINARIES = mapOf(
        "libnode.so" to "node",
        "libgit.so" to "git",
        "librust.so" to "rustc"
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

        // ---- Core shell (from jniLibs in the terminal module) ----
        linkNativeBinary(nativeLibDir, binDir, "libbash.so", "bash")
        val busyboxPath = linkNativeBinary(nativeLibDir, binDir, "libbusybox.so", "busybox")
        if (busyboxPath != null) {
            createBusyboxSymlinks(busyboxPath, binDir)
        }

        // ---- Python (from jniLibs — prebuilt PIE wrapper fetched from S3) ----
        // libpython_shell.so is a PIE executable that calls Py_BytesMain.
        // Links against libpython3.14.so which is also in nativeLibDir.
        val pythonShell = linkNativeBinary(nativeLibDir, binDir, "libpython_shell.so", "python3")
        if (pythonShell != null) {
            symlinkIfMissing(pythonShell, File(binDir, "python"))
        }

        // ---- Native tool binaries (lib*.so in nativeLibDir → usr/bin/*) ----
        // Prefers S3-fetched native lib versions over asset versions.
        for ((soName, binName) in NATIVE_TOOL_BINARIES) {
            val linked = linkNativeBinary(nativeLibDir, binDir, soName, binName)
            if (linked != null) {
                Log.d(TAG, "Linked native tool: $soName -> $binName")
            }
        }

        // ---- Asset tool binaries (fallback from filesDir/python/bin/) ----
        val hostBinDir = File(filesDir, "python/bin")
        for (tool in ASSET_TOOL_BINARIES) {
            val target = File(binDir, tool)
            if (target.exists()) continue  // prefer jniLibs version if already linked
            val source = File(hostBinDir, tool)
            if (source.exists()) {
                symlinkIfMissing(source.absolutePath, target)
                source.setExecutable(true)
            }
        }

        // ---- npm bootstrap ----
        // npm ships as a tarball in the host app's assets, extracted to
        // filesDir/python/lib/node_modules/npm/. We create bin stubs.
        bootstrapNpm(filesDir, binDir)

        // ---- pip bootstrap ----
        // Once python3 exists, run ensurepip on first launch.
        bootstrapPip(binDir, prefixDir)

        // ---- Shell config ----
        writeBashrc(homeDir, prefixDir)
        writeProfile(homeDir, binDir)

        Log.d(TAG, "Environment ready: prefix=$prefixDir")
    }

    // ---- Internal helpers ----

    private fun linkNativeBinary(
        nativeLibDir: String,
        binDir: File,
        soName: String,
        linkName: String
    ): String? {
        val source = File(nativeLibDir, soName)
        val target = File(binDir, linkName)

        if (!source.exists()) {
            Log.d(TAG, "Native binary not found (may not be needed): $source")
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
            target.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to symlink $soName -> $linkName", e)
            null
        }
    }

    private fun symlinkIfMissing(sourcePath: String, target: File) {
        if (target.exists()) return
        try {
            Runtime.getRuntime().exec(arrayOf("ln", "-sf", sourcePath, target.absolutePath)).waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create symlink: ${target.name}", e)
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
     * npm is bundled as a directory (node_modules/npm/) by the host app.
     * Create bin/npm and bin/npx stubs that invoke it via node.
     */
    private fun bootstrapNpm(filesDir: File, binDir: File) {
        val npmModuleDir = File(filesDir, "python/lib/node_modules/npm")
        if (!npmModuleDir.exists()) {
            // Host app hasn't extracted npm yet — skip silently.
            // This will be retried on next launch.
            Log.d(TAG, "npm not found at $npmModuleDir — skipping npm bootstrap")
            return
        }

        val node = File(binDir, "node")
        if (!node.exists()) return

        // bin/npm → shell script that calls node with npm-cli.js
        val npmBin = File(binDir, "npm")
        if (!npmBin.exists()) {
            npmBin.writeText(
                """
                #!/bin/sh
                exec "${node.absolutePath}" "${npmModuleDir.absolutePath}/bin/npm-cli.js" "${'$'}@"
                """.trimIndent() + "\n"
            )
            npmBin.setExecutable(true)
        }

        // bin/npx → shell script that calls node with npx-cli.js
        val npxBin = File(binDir, "npx")
        if (!npxBin.exists()) {
            npxBin.writeText(
                """
                #!/bin/sh
                exec "${node.absolutePath}" "${npmModuleDir.absolutePath}/bin/npx-cli.js" "${'$'}@"
                """.trimIndent() + "\n"
            )
            npxBin.setExecutable(true)
        }

        Log.d(TAG, "npm bootstrapped: ${npmBin.absolutePath}")
    }

    /**
     * Bootstrap pip via python3 -m ensurepip (one-time).
     */
    private fun bootstrapPip(binDir: File, prefixDir: File) {
        val python = File(binDir, "python3")
        val pip = File(binDir, "pip3")
        if (!python.exists() || pip.exists()) return

        Log.d(TAG, "Bootstrapping pip via ensurepip...")
        try {
            val pb = ProcessBuilder(
                python.absolutePath, "-m", "ensurepip", "--default-pip"
            )
            pb.environment()["HOME"] = prefixDir.parentFile?.absolutePath ?: "/tmp"
            pb.environment()["TMPDIR"] = File(prefixDir.parentFile, "tmp").absolutePath
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val rc = proc.waitFor()
            if (rc == 0) {
                // Create pip → pip3 symlink
                symlinkIfMissing(pip.absolutePath, File(binDir, "pip"))
                Log.d(TAG, "pip bootstrapped successfully")
            } else {
                Log.w(TAG, "ensurepip failed (rc=$rc): $output")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensurepip failed", e)
        }
    }

    private fun writeBashrc(homeDir: File, prefixDir: File) {
        val bashrc = File(homeDir, ".bashrc")
        if (bashrc.exists()) return

        bashrc.writeText(
            """
            # Cory shell environment
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
            """.trimIndent() + "\n"
        )
    }

    private fun writeProfile(homeDir: File, binDir: File) {
        val profile = File(homeDir, ".profile")
        if (profile.exists()) return

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
