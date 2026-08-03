package com.linuxterminal.mobile.terminal

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Manages a terminal session connected to a shell process.
 * Reads stdout/stderr from the process and feeds it to the emulator.
 * Sends keyboard input from the emulator to the process stdin.
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

    private var process: Process? = null
    private var processInput: OutputStream? = null
    private var processOutput: InputStream? = null
    private var processError: InputStream? = null

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "TerminalSession-IO").apply { isDaemon = true }
    }

    @Volatile
    private var isRunning = false

    /** Start the session with the given command */
    fun start(command: List<String>, env: Map<String, String> = emptyMap(), cwd: String? = null) {
        try {
            Log.i(TAG, "Starting: ${command.joinToString(" ")}")

            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(false)

            // Set up environment
            val processEnv = pb.environment()
            env.forEach { (key, value) ->
                processEnv[key] = value
            }

            // Set working directory
            cwd?.let { pb.directory(java.io.File(it)) }

            val proc = pb.start()
            process = proc
            processInput = proc.outputStream
            processOutput = proc.inputStream
            processError = proc.errorStream

            isRunning = true
            listener?.onProcessStarted()

            // Start output reader thread
            executor.execute { readOutputLoop() }
            // Start error reader thread
            executor.execute { readErrorLoop() }
            // Start input writer thread
            executor.execute { writeInputLoop() }
            // Start process waiter
            executor.execute { waitProcessLoop() }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start process", e)
            listener?.onError("Failed to start: ${e.message}")
        }
    }

    private fun readOutputLoop() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            val stream = processOutput ?: return
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

    private fun readErrorLoop() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            val stream = processError ?: return
            while (isRunning) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    // Feed stderr to emulator too (so error messages appear)
                    emulator.processBytes(buffer, 0, read)
                }
            }
        } catch (e: IOException) {
            if (isRunning) {
                Log.e(TAG, "Error read error", e)
            }
        }
    }

    private fun writeInputLoop() {
        try {
            val stream = processInput ?: return
            while (isRunning) {
                val data = emulator.inputQueue.poll()
                if (data != null) {
                    stream.write(data)
                    stream.flush()
                } else {
                    Thread.sleep(10)
                }
            }
        } catch (e: IOException) {
            if (isRunning) {
                Log.e(TAG, "Input write error", e)
            }
        } catch (e: InterruptedException) {
            // Normal shutdown
        }
    }

    private fun waitProcessLoop() {
        try {
            val proc = process ?: return
            val exitCode = proc.waitFor()
            isRunning = false
            Log.i(TAG, "Process exited with code $exitCode")
            listener?.onProcessExited(exitCode)
        } catch (e: InterruptedException) {
            // Normal shutdown
        }
    }

    /** Write data directly to the process stdin */
    fun write(data: ByteArray) {
        try {
            processInput?.let {
                it.write(data)
                it.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Write error", e)
        }
    }

    /** Send a string to the process */
    fun write(text: String) {
        write(text.toByteArray())
    }

    /** Send Ctrl+C to interrupt */
    fun sendCtrlC() {
        emulator.sendCtrlKey('c')
    }

    /** Check if the session is running */
    fun isAlive(): Boolean = isRunning

    /** Stop the session and clean up */
    fun stop() {
        isRunning = false
        try {
            process?.destroy()
            process?.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping process", e)
        }
        executor.shutdownNow()
    }
}
