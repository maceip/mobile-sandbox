package com.cory.app

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.ai.assistance.operit.terminal.TerminalBootstrap
import java.io.File
import java.io.FileOutputStream

/**
 * Host-app extension of [TerminalBootstrap].
 *
 * [TerminalBootstrap] handles the core shell (bash + busybox + git). This
 * class handles extension tools that come from the app's assets: python3,
 * node, ripgrep, plus the generated shell wrappers for pip, npm, npx.
 *
 * Runs after TerminalBootstrap on every app launch. Idempotent.
 */
object CoryTerminalRuntime {
    private const val TAG = "CoryTerminalRuntime"
    private const val RUNTIME_LAYOUT_VERSION = 1

    /**
     * Tool binaries we expect to find in `filesDir/python/bin/` after
     * asset extraction. Each is optional — missing binaries are logged
     * to the bootstrap status file so the user sees the problem on their
     * first shell, instead of hitting "command not found" later.
     *
     * Note: `git` is NOT in this list. It ships as libgit_cli.so through
     * the jniLibs channel (see CMakeLists.txt and TerminalBootstrap.kt).
     */
    private val ASSET_TOOL_BINARIES = listOf(
        "node",
        "rg",
        "busybox",
        "python3",
        "python3.14"
    )

    fun ensureReady(context: Context) {
        TerminalBootstrap.ensureEnvironment(context)
        syncBundledRuntime(context.assets, context.filesDir)
        linkBundledTools(context.filesDir)
    }

    private fun syncBundledRuntime(assets: AssetManager, filesDir: File) {
        val runtimeRoot = File(filesDir, "python")
        val stampFile = File(runtimeRoot, ".runtime-layout-version")
        val expectedStamp = RUNTIME_LAYOUT_VERSION.toString()
        if (stampFile.readTextOrNull() == expectedStamp) {
            return
        }

        if (runtimeRoot.exists()) {
            runtimeRoot.deleteRecursively()
        }
        runtimeRoot.mkdirs()
        copyAssetTree(assets, "python", runtimeRoot)
        stampFile.writeText(expectedStamp)
    }

    private fun copyAssetTree(assets: AssetManager, assetPath: String, outputDir: File) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            copyAssetFile(assets, assetPath, outputDir)
            return
        }
        outputDir.mkdirs()
        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childOutputName = if (child.endsWith("-")) child.dropLast(1) else child
            copyAssetTree(assets, childAssetPath, File(outputDir, childOutputName))
        }
    }

    private fun copyAssetFile(assets: AssetManager, assetPath: String, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        assets.open(assetPath).use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
        if (assetPath.startsWith("python/bin/")) {
            outputFile.setExecutable(true, true)
        }
    }

    private fun linkBundledTools(filesDir: File) {
        val runtimeRoot = File(filesDir, "python")
        val bundledBin = File(runtimeRoot, "bin")
        val bundledLib = File(runtimeRoot, "lib")
        val usrBin = File(filesDir, "usr/bin")
        val homeDir = File(filesDir, "home")
        if (!usrBin.exists()) {
            usrBin.mkdirs()
        }

        val missingTools = mutableListOf<String>()

        if (!bundledBin.exists()) {
            Log.e(TAG, "CRITICAL: $bundledBin does not exist — python/node/rg all missing")
            missingTools += ASSET_TOOL_BINARIES
        } else {
            for (tool in ASSET_TOOL_BINARIES) {
                val source = File(bundledBin, tool)
                if (!source.exists()) {
                    Log.w(TAG, "asset binary missing: $tool")
                    missingTools += tool
                    continue
                }
                val target = File(usrBin, tool)
                symlinkOrCopy(source, target)
            }

            // Create `python` alias if python3 landed
            val python3 = File(usrBin, "python3")
            if (python3.exists()) {
                symlinkOrCopy(python3, File(usrBin, "python"))
            }
        }

        // Shell wrappers for pip and npm (these are cheap to rewrite every launch)
        writePipWrappers(usrBin)
        writeNpmWrappers(usrBin, bundledLib)

        // Append missing-tool warnings to the bootstrap status file so the
        // user sees them when their shell starts. TerminalBootstrap may
        // have already written errors for bash/busybox — we append.
        appendMissingToolWarnings(homeDir, missingTools)
    }

    private fun writePipWrappers(usrBin: File) {
        val python3 = File(usrBin, "python3")
        if (!python3.exists()) return  // pip is useless without python3

        val pip = File(usrBin, "pip")
        writeShellWrapper(
            pip,
            """
            |#!/system/bin/sh
            |PYTHON_BIN="${python3.absolutePath}"
            |if ! "${'$'}PYTHON_BIN" -c "import pip" >/dev/null 2>&1; then
            |  "${'$'}PYTHON_BIN" -m ensurepip --upgrade || exit ${'$'}?
            |fi
            |exec "${'$'}PYTHON_BIN" -m pip "${'$'}@"
            """.trimMargin()
        )
        writeShellWrapper(
            File(usrBin, "pip3"),
            """
            |#!/system/bin/sh
            |exec "${pip.absolutePath}" "${'$'}@"
            """.trimMargin()
        )
    }

    private fun writeNpmWrappers(usrBin: File, bundledLib: File) {
        val node = File(usrBin, "node")
        if (!node.exists()) return

        val npmCli = File(bundledLib, "node_modules/npm/bin/npm-cli.js")
        val npxCli = File(bundledLib, "node_modules/npm/bin/npx-cli.js")

        if (npmCli.exists()) {
            writeShellWrapper(
                File(usrBin, "npm"),
                """
                |#!/system/bin/sh
                |exec "${node.absolutePath}" "${npmCli.absolutePath}" "${'$'}@"
                """.trimMargin()
            )
        }
        if (npxCli.exists()) {
            writeShellWrapper(
                File(usrBin, "npx"),
                """
                |#!/system/bin/sh
                |exec "${node.absolutePath}" "${npxCli.absolutePath}" "${'$'}@"
                """.trimMargin()
            )
        }
    }

    /**
     * Append asset-tool errors to the bootstrap status banner that
     * TerminalBootstrap wrote earlier. The user sees this on login.
     */
    private fun appendMissingToolWarnings(homeDir: File, missing: List<String>) {
        if (missing.isEmpty()) return
        val statusFile = File(homeDir, ".cory-bootstrap-status")
        val header = if (statusFile.exists()) "" else
            "\u001B[1;31m[cory bootstrap]\u001B[0m missing tool binaries:\n"
        val body = buildString {
            if (header.isNotEmpty()) append(header)
            else append("\u001B[33mmissing tool binaries:\u001B[0m\n")
            for (t in missing) {
                append("  \u001B[33m!\u001B[0m $t\n")
            }
            append("\n")
        }
        if (statusFile.exists()) {
            statusFile.appendText(body)
        } else {
            statusFile.writeText(body)
        }
    }

    private fun symlinkOrCopy(source: File, target: File) {
        if (target.exists()) {
            target.delete()
        }
        try {
            val process = ProcessBuilder("ln", "-sf", source.absolutePath, target.absolutePath)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "symlink failed for ${source.name}, falling back to copy", e)
        }
        source.copyTo(target, overwrite = true)
        target.setExecutable(true, true)
    }

    private fun writeShellWrapper(target: File, content: String) {
        target.parentFile?.mkdirs()
        target.writeText(content.trimEnd() + "\n")
        target.setExecutable(true, true)
    }

    private fun File.readTextOrNull(): String? = if (exists()) readText() else null
}
