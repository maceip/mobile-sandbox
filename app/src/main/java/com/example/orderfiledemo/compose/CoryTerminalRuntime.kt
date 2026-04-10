package com.example.orderfiledemo.compose

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.ai.assistance.operit.terminal.TerminalBootstrap
import java.io.File
import java.io.FileOutputStream

object CoryTerminalRuntime {
    private const val TAG = "CoryTerminalRuntime"

    fun ensureReady(context: Context) {
        TerminalBootstrap.ensureEnvironment(context)
        extractBundledTools(context.assets, context.filesDir)
        linkBundledTools(context.filesDir)
    }

    private fun extractBundledTools(assets: AssetManager, filesDir: File) {
        val assetBin = "python/bin"
        val names = assets.list(assetBin) ?: return
        val outputDir = File(filesDir, "python/bin")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        for (name in names) {
            val sourcePath = "$assetBin/$name"
            val outputName = if (name.endsWith("-")) name.dropLast(1) else name
            val outputFile = File(outputDir, outputName)
            assets.open(sourcePath).use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
            outputFile.setExecutable(true, true)
        }
    }

    private fun linkBundledTools(filesDir: File) {
        val bundledBin = File(filesDir, "python/bin")
        val usrBin = File(filesDir, "usr/bin")
        if (!bundledBin.exists()) {
            return
        }
        if (!usrBin.exists()) {
            usrBin.mkdirs()
        }
        val tools = listOf("node", "rg", "busybox")
        for (tool in tools) {
            val source = File(bundledBin, tool)
            if (!source.exists()) {
                continue
            }
            val target = File(usrBin, tool)
            symlinkOrCopy(source, target)
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
}
