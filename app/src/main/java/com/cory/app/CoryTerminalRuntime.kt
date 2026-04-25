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
 * [TerminalBootstrap] handles the core shell (bash + busybox). This class
 * handles extension tools that come from the app's assets: python3, node,
 * ripgrep, git, plus the generated shell wrappers for pip, npm, npx.
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
     * `git` is built by CMake from libgit2's examples + our worktree.c
     * and written directly into the generated assets dir under
     * python/bin/git (see app/src/main/cpp/CMakeLists.txt and
     * app/build.gradle's mergeDebugAssets -> buildCMake* wiring).
     */
    private val ASSET_TOOL_BINARIES = listOf(
        "node",
        "rg",
        "busybox",
        "python3",
        "python3.14",
        "git"
    )

    private val NATIVE_TOOL_BINARIES = mapOf(
        "node" to "libnode.so",
        "python3" to "libpython3.so",
        "python3.14" to "libpython3.so",
        "rg" to "librg.so",
        "git" to "libgit.so",
        "busybox" to "libbusybox.so",
    )

    fun ensureReady(context: Context) {
        TerminalBootstrap.ensureEnvironment(context)
        syncBundledRuntime(context.assets, context.filesDir)
        linkBundledTools(context.filesDir, context.applicationInfo.nativeLibraryDir)
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

    private fun linkBundledTools(filesDir: File, nativeLibDir: String) {
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
            Log.e(TAG, "CRITICAL: $bundledBin does not exist — python/node/rg/git all missing")
            missingTools += ASSET_TOOL_BINARIES
        } else {
            for (tool in ASSET_TOOL_BINARIES) {
                val target = File(usrBin, tool)
                val nativeName = NATIVE_TOOL_BINARIES[tool]
                if (nativeName != null) {
                    val nativeSource = File(nativeLibDir, nativeName)
                    if (nativeSource.exists()) {
                        // Device Farm SELinux can deny direct exec() from app-private asset paths
                        // like files/python/bin/*. Prefer nativeLibraryDir when available.
                        symlinkOrCopy(nativeSource, target)
                        continue
                    }
                }

                val source = File(bundledBin, tool)
                if (!source.exists()) {
                    Log.w(TAG, "asset binary missing: $tool")
                    missingTools += tool
                    continue
                }
                symlinkOrCopy(source, target)
            }

            // Create `python` alias if python3 landed
            val python3 = File(usrBin, "python3")
            if (python3.exists()) {
                symlinkOrCopy(python3, File(usrBin, "python"))
            }
        }

        // Shell wrappers for pip and npm (these are cheap to rewrite every launch)
        writePipWrappers(usrBin, bundledLib)
        writeNpmWrappers(usrBin, bundledLib)

        // Append missing-tool warnings to the bootstrap status file so the
        // user sees them when their shell starts. TerminalBootstrap may
        // have already written errors for bash/busybox — we append.
        appendMissingToolWarnings(homeDir, missingTools)
    }

    private fun writePipWrappers(usrBin: File, bundledLib: File) {
        val python3 = File(usrBin, "python3")
        if (!python3.exists()) return  // pip is useless without python3

        val pipModule = File(bundledLib, "python3.14/ensurepip/_bundled/pip-25.1.1-py3-none-any.whl")
        val setuputilsModule = File(bundledLib, "python3.14/_distutils_hack/__init__.py")

        val pip = File(usrBin, "pip.sh")
        writeShellWrapper(
            pip,
            """
            |#!/system/bin/sh
            |PYTHON_BIN="${python3.absolutePath}"
            |PYTHONPIP_PATH="${pipModule.absolutePath}"
            |PYTHONSETUPTOOLS_PATH="${setuputilsModule.absolutePath}"
            |if [ -d "${bundledLib.absolutePath}/python3.14/lib-dynload" ]; then
            |  export PYTHONPATH="${bundledLib.absolutePath}/python3.14/lib-dynload:${'$'}PYTHONPIP_PATH:${'$'}PYTHONSETUPTOOLS_PATH"
            |else
            |  export PYTHONPATH="${'$'}PYTHONPIP_PATH:${'$'}PYTHONSETUPTOOLS_PATH"
            |fi
            |if ! "${'$'}PYTHON_BIN" -c "import pip" >/dev/null 2>&1; then
            |  "${'$'}PYTHON_BIN" -m ensurepip --upgrade || exit ${'$'}?
            |fi
            |exec "${'$'}PYTHON_BIN" -m pip "${'$'}@"
            """.trimMargin()
        )
        writeCommandShim(
            File(usrBin, "pip"),
            pip,
        )
        writeCommandShim(
            File(usrBin, "pip3"),
            pip,
        )
    }

    private fun writeNpmWrappers(usrBin: File, bundledLib: File) {
        val node = File(usrBin, "node")
        if (!node.exists()) return

        val npmRoot = ensureBundledNpmExpanded(bundledLib, node) ?: return
        val npmCli = File(npmRoot, "bin/npm-cli.js")
        val npxCli = File(npmRoot, "bin/npx-cli.js")

        if (npmCli.exists()) {
            writeShellWrapper(
                File(usrBin, "npm-runner.sh"),
                """
                |#!/system/bin/sh
                |exec "${node.absolutePath}" "${npmCli.absolutePath}" "${'$'}@"
                """.trimMargin()
            )
            writeShellWrapper(
                File(usrBin, "npm"),
                """
                |#!/system/bin/sh
                |exec /system/bin/sh "${File(usrBin, "npm-runner.sh").absolutePath}" "${'$'}@"
                """.trimMargin()
            )
        }
        if (npxCli.exists()) {
            writeShellWrapper(
                File(usrBin, "npx-runner.sh"),
                """
                |#!/system/bin/sh
                |exec "${node.absolutePath}" "${npxCli.absolutePath}" "${'$'}@"
                """.trimMargin()
            )
            writeShellWrapper(
                File(usrBin, "npx"),
                """
                |#!/system/bin/sh
                |exec /system/bin/sh "${File(usrBin, "npx-runner.sh").absolutePath}" "${'$'}@"
                """.trimMargin()
            )
        }
    }

    private fun ensureBundledNpmExpanded(bundledLib: File, node: File): File? {
        val nodeModulesRoot = File(bundledLib, "node_modules")
        val npmRoot = File(nodeModulesRoot, "npm")
        val npmCli = File(npmRoot, "bin/npm-cli.js")
        if (npmCli.exists()) return npmRoot

        val npmTarball = File(nodeModulesRoot, "npm.tgz")
        if (!npmTarball.exists()) {
            Log.w(TAG, "npm tarball missing at ${npmTarball.absolutePath}")
            return null
        }

        val extractScript = """
            const fs = require('fs');
            const path = require('path');
            const zlib = require('zlib');
            const tar = require('tar');
            const npmRoot = process.argv[1];
            const tgz = process.argv[2];
            const pkgDir = path.join(npmRoot, 'package');
            if (!fs.existsSync(path.join(pkgDir, 'bin', 'npm-cli.js'))) {
              fs.rmSync(pkgDir, { recursive: true, force: true });
              fs.mkdirSync(pkgDir, { recursive: true });
              tar.x({ cwd: npmRoot, file: tgz, sync: true, gzip: true });
            }
            const finalDir = path.join(npmRoot, 'npm');
            if (!fs.existsSync(path.join(finalDir, 'bin', 'npm-cli.js'))) {
              fs.rmSync(finalDir, { recursive: true, force: true });
              fs.renameSync(pkgDir, finalDir);
            }
            process.stdout.write(finalDir);
        """.trimIndent()

        return try {
            val process = ProcessBuilder(
                node.absolutePath,
                "-e",
                extractScript,
                npmRoot.absolutePath,
                npmTarball.absolutePath,
            )
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
            val code = process.waitFor()
            if (code != 0) {
                Log.w(TAG, "npm expand helper exited $code: $out")
                return null
            }
            File(out).takeIf { File(it, "bin/npm-cli.js").exists() }
        } catch (e: Exception) {
            Log.w(TAG, "failed to expand bundled npm", e)
            null
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

    private fun writeCommandShim(target: File, script: File) {
        writeShellWrapper(
            target,
            """
            |#!/system/bin/sh
            |exec /system/bin/sh "${script.absolutePath}" "${'$'}@"
            """.trimMargin()
        )
    }

    private fun File.readTextOrNull(): String? = if (exists()) readText() else null
}
