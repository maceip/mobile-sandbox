package com.ai.assistance.operit.terminal

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Manages a pseudo-terminal backed subprocess.
 *
 * The PTY master fd is used for bidirectional I/O with the child process.
 * [stdout] reads from the child; [stdin] writes to it.
 */
open class Pty(
    val process: PtyProcess,
    private val masterPfd: ParcelFileDescriptor,
    val stdout: InputStream,
    val stdin: OutputStream
) {
    /** The raw master fd integer (for JNI calls like window resize). */
    val masterFd: Int get() = masterPfd.fd

    fun waitFor(): Int = process.waitFor()

    fun destroy() {
        process.destroy()
        try {
            stdout.close()
            stdin.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing PTY streams", e)
        }
        try {
            masterPfd.close()
        } catch (_: IOException) { }
    }

    /**
     * Set the PTY window size (rows x cols).  Call this whenever the
     * terminal view is resized.
     */
    open fun setWindowSize(rows: Int, cols: Int): Boolean {
        if (masterFd < 0) return false
        val rc = setPtyWindowSize(masterFd, rows, cols)
        if (rc != 0) Log.e(TAG, "Failed to set window size ${rows}x${cols}")
        return rc == 0
    }

    /**
     * Enable or disable UTF-8 aware line editing in the kernel driver.
     */
    open fun setUTF8Mode(enabled: Boolean): Boolean {
        if (masterFd < 0) return false
        return setPtyUTF8Mode(masterFd, enabled) == 0
    }

    companion object {
        private const val TAG = "Pty"

        init {
            try {
                System.loadLibrary("pty")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libpty.so", e)
            }
        }

        /**
         * Spawn a new subprocess inside a PTY.
         *
         * @param command     argv array – command[0] is the executable path.
         * @param environment KEY=VALUE pairs.
         * @param workingDir  initial cwd for the child.
         * @param rows        initial terminal height (default 24).
         * @param cols        initial terminal width  (default 80).
         */
        @Throws(IOException::class)
        fun start(
            command: Array<String>,
            environment: Map<String, String>,
            workingDir: File,
            rows: Int = 24,
            cols: Int = 80
        ): Pty {
            val envArray = environment.map { "${it.key}=${it.value}" }.toTypedArray()

            val info = createSubprocess(command, envArray, workingDir.absolutePath, rows, cols)
                ?: throw IOException("forkpty failed")
            val pid = info[0]
            val masterFdInt = info[1]

            if (pid <= 0 || masterFdInt < 0) {
                throw IOException("createSubprocess returned invalid pid=$pid fd=$masterFdInt")
            }

            // Wrap the raw fd safely – ParcelFileDescriptor owns the fd now.
            val pfd = ParcelFileDescriptor.adoptFd(masterFdInt)
            val fd = pfd.fileDescriptor

            val process = PtyProcess(pid)
            return Pty(
                process = process,
                masterPfd = pfd,
                stdout = FileInputStream(fd),
                stdin = FileOutputStream(fd)
            )
        }

        // --- JNI declarations ---

        private external fun createSubprocess(
            cmdArray: Array<String>,
            envArray: Array<String>,
            workingDir: String,
            rows: Int,
            cols: Int
        ): IntArray?

        internal external fun waitFor(pid: Int): Int

        private external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int): Int

        private external fun setPtyUTF8Mode(fd: Int, enabled: Boolean): Int
    }
}

/**
 * Lightweight Process wrapper around a raw PID obtained from forkpty().
 *
 * Unlike the JDK [Process] returned by [ProcessBuilder], this does not
 * own the child's stdio (the PTY master fd handles that).
 */
class PtyProcess(val pid: Int) : Process() {
    @Volatile private var exited = false
    @Volatile private var cachedExitCode = -1

    override fun destroy() {
        // Graceful: SIGHUP → allow cleanup
        try { android.os.Process.sendSignal(pid, 1) } catch (_: Exception) { }
        // Hard kill after a short window (the caller can waitFor if needed)
        try { android.os.Process.sendSignal(pid, 9) } catch (_: Exception) { }
    }

    override fun exitValue(): Int {
        if (exited) return cachedExitCode
        // Check if process is still alive (signal 0 = existence probe)
        try {
            android.os.Process.sendSignal(pid, 0)
            throw IllegalThreadStateException("Process $pid is still running")
        } catch (_: IllegalArgumentException) {
            // pid doesn't exist → already dead, but we don't have the code
            // unless waitFor was called. Fall through.
        }
        return cachedExitCode
    }

    override fun waitFor(): Int {
        if (exited) return cachedExitCode
        cachedExitCode = Pty.waitFor(pid)
        exited = true
        return cachedExitCode
    }

    // stdio is handled via the PTY master fd, not these streams.
    override fun getErrorStream(): InputStream = object : InputStream() { override fun read() = -1 }
    override fun getInputStream(): InputStream = object : InputStream() { override fun read() = -1 }
    override fun getOutputStream(): OutputStream = object : OutputStream() { override fun write(b: Int) {} }
}
