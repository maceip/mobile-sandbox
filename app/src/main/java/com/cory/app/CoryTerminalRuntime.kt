package com.cory.app

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.ai.assistance.operit.terminal.TerminalBootstrap
import java.io.File
import java.io.FileOutputStream

object CoryTerminalRuntime {
    private const val TAG = "CoryTerminalRuntime"
    private const val RUNTIME_LAYOUT_VERSION = 1

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
        if (!bundledBin.exists()) {
            return
        }
        if (!usrBin.exists()) {
            usrBin.mkdirs()
        }
        val tools = listOf("node", "rg", "busybox", "python3", "python3.14")
        for (tool in tools) {
            val source = File(bundledBin, tool)
            if (!source.exists()) {
                continue
            }
            val target = File(usrBin, tool)
            symlinkOrCopy(source, target)
        }
        val python3 = File(usrBin, "python3")
        if (python3.exists()) {
            symlinkOrCopy(python3, File(usrBin, "python"))
        }
        writeShellWrapper(
            File(usrBin, "pip"),
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
            |exec "${File(usrBin, "pip").absolutePath}" "${'$'}@"
            """.trimMargin()
        )

        val node = File(usrBin, "node")
        val npmCli = File(bundledLib, "node_modules/npm/bin/npm-cli.js")
        val npxCli = File(bundledLib, "node_modules/npm/bin/npx-cli.js")
        if (node.exists() && npmCli.exists()) {
            writeShellWrapper(
                File(usrBin, "npm"),
                """
                |#!/system/bin/sh
                |exec "${node.absolutePath}" "${npmCli.absolutePath}" "${'$'}@"
                """.trimMargin()
            )
        }
        if (node.exists() && npxCli.exists()) {
            writeShellWrapper(
                File(usrBin, "npx"),
                """
                |#!/system/bin/sh
                |exec "${node.absolutePath}" "${npxCli.absolutePath}" "${'$'}@"
                """.trimMargin()
            )
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
