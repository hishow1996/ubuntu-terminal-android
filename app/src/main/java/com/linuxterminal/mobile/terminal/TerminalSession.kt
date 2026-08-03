package com.linuxterminal.mobile.terminal

import android.util.Log
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Manages a terminal session connected to a shell process via PTY.
 * Uses the JNI forkpty implementation for proper terminal handling.
 */
class TerminalSession(
    private val emulator: TerminalEmulator
) {
    companion object {
        private const val TAG = "TerminalSession"
        private const val READ_BUFFER_SIZE = 8192
    }

    interface SessionListener {
        fun onProcessStarted()
        fun onProcessExited(exitCode: Int)
        fun onOutput(data: ByteArray)
        fun onError(message: String)
        fun onTitleChanged(title: String)
    }

    var listener: SessionListener? = null

    private var pty: Pty? = null
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "TerminalSession-IO").apply { isDaemon = true }
    }

    /** Start the session with a PTY */
    fun startWithPty(ptyInstance: Pty) {
        pty = ptyInstance
        isRunning = true
        listener?.onProcessStarted()

        // Start output reader thread
        executor.execute { readOutputLoop() }
        // Start process waiter
        executor.execute { waitForProcess() }
    }

    private fun readOutputLoop() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            val stream = pty?.stdout ?: return
            while (isRunning) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    emulator.processBytes(buffer, 0, read)
                    listener?.onOutput(buffer.copyOfRange(0, read))
                }
            }
        } catch (e: IOException) {
            if (isRunning) {
                Log.e(TAG, "Output read error", e)
            }
        }
    }

    private fun waitForProcess() {
        try {
            val exitCode = pty?.waitFor() ?: -1
            isRunning = false
            listener?.onProcessExited(exitCode)
        } catch (e: InterruptedException) {
            // Normal shutdown
        }
    }

    /** Write data to the process stdin (via PTY master) */
    fun write(data: ByteArray) {
        try {
            pty?.stdin?.write(data)
            pty?.stdin?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Write error", e)
        }
    }

    fun write(text: String) {
        write(text.toByteArray())
    }

    fun sendCtrlC() {
        emulator.sendCtrlKey('c')
    }

    fun isAlive(): Boolean = isRunning

    /** Set terminal window size */
    fun setWindowSize(rows: Int, cols: Int) {
        // Window size will be set via PTY if supported
    }

    fun stop() {
        isRunning = false
        try {
            pty?.process?.destroy()
            pty?.process?.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping process", e)
        }
        executor.shutdownNow()
    }
}
