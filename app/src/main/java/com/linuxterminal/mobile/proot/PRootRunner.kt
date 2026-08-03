package com.linuxterminal.mobile.proot

import android.content.Context
import android.util.Log
import com.linuxterminal.mobile.terminal.TerminalSession
import java.io.File

/**
 * Builds and launches the PRoot command to run Ubuntu.
 * PRoot uses ptrace to intercept syscalls and translate paths,
 * enabling a real Ubuntu environment without root access.
 */
class PRootRunner(private val context: Context, private val envSetup: EnvironmentSetup) {

    companion object {
        private const val TAG = "PRootRunner"
    }

    /**
     * Build the PRoot command line to launch Ubuntu bash.
     *
     * PRoot options:
     * -r <path>     : Set root filesystem path
     * -b <src:dst>  : Bind mount (src on host -> dst in guest)
     * -b <path>     : Bind mount (same path)
     * -w <path>     : Set working directory
     * --link2symlink: Convert hard links to symbolic links (saves space)
     * --kill-on-exit: Kill child processes when PRoot exits
     * -0            : Use PRoot's built-in process tracing (default mode)
     */
    fun buildCommand(
        rootfsPath: String,
        bindMounts: List<String> = envSetup.getBindMounts(),
        workingDir: String = "/root",
        command: String = "/bin/bash"
    ): List<String> {
        val cmd = mutableListOf<String>()

        // PRoot binary
        cmd.add(envSetup.prootBinary.absolutePath)

        // Root filesystem
        cmd.add("-r")
        cmd.add(rootfsPath)

        // Bind mounts
        for (mount in bindMounts) {
            cmd.add("-b")
            cmd.add(mount)
        }

        // Additional mounts for Android-specific paths
        cmd.add("-b")
        cmd.add("/proc/self/fd:/dev/fd")
        cmd.add("-b")
        cmd.add("/proc/self/fd/0:/dev/stdin")
        cmd.add("-b")
        cmd.add("/proc/self/fd/1:/dev/stdout")
        cmd.add("-b")
        cmd.add("/proc/self/fd/2:/dev/stderr")

        // Link2symlink for space saving (important on Android)
        cmd.add("--link2symlink")

        // Set working directory
        cmd.add("-w")
        cmd.add(workingDir)

        // Set root ID
        cmd.add("-0")
        cmd.add("root")

        // Kill processes on exit
        cmd.add("--kill-on-exit")

        // The command to run in the chroot
        cmd.add("/usr/bin/env")
        cmd.add("-i")
        cmd.add("HOME=/root")
        cmd.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        cmd.add("TERM=xterm-256color")
        cmd.add("LANG=C.UTF-8")
        cmd.add("LANGUAGE=en_US:en")
        cmd.add("SHELL=/bin/bash")
        cmd.add("USER=root")
        cmd.add("LOGNAME=root")
        cmd.add("HOSTNAME=ubuntu")
        cmd.add("PREFIX=/usr")
        cmd.add("LC_ALL=C.UTF-8")

        // Add Android-specific environment variables
        cmd.add("ANDROID_DATA=/data")
        cmd.add("ANDROID_ROOT=/system")

        cmd.add(command)

        return cmd
    }

    /** Build command for interactive bash login shell */
    fun buildInteractiveCommand(): List<String> {
        return buildCommand(
            rootfsPath = envSetup.getRootfsPath(),
            workingDir = "/root",
            command = "/bin/bash"
        )
    }

    /** Build command for initial setup (apt update, install packages) */
    fun buildSetupCommand(): List<String> {
        val cmd = buildCommand(
            rootfsPath = envSetup.getRootfsPath(),
            workingDir = "/root",
            command = "/bin/bash"
        )
        // Add setup script as argument
        return cmd.dropLast(1) + listOf(
            "-c",
            "apt-get update && apt-get install -y --no-install-recommends " +
                "sudo wget curl nano vim less procps iproute2 " +
                "&& apt-get clean && rm -rf /var/lib/apt/lists/*"
        )
    }

    /** Get environment variables for PRoot */
    fun getEnvironment(): Map<String, String> {
        return mapOf(
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "LANGUAGE" to "en_US:en",
            "SHELL" to "/bin/bash",
            "USER" to "root",
            "LOGNAME" to "root",
            "HOSTNAME" to "ubuntu",
            "PROOT_NO_SECCOMP" to "1",
            "PROOT_TMP_DIR" to envSetup.getProotTmpDir()
        )
    }

    /** Check if PRoot binary is ready */
    fun isReady(): Boolean {
        return envSetup.prootBinary.exists() &&
               envSetup.prootBinary.canExecute() &&
               envSetup.isRootfsInstalled()
    }

    /** Test PRoot by running a simple command */
    fun testPRoot(): Boolean {
        return try {
            val cmd = buildCommand(
                rootfsPath = envSetup.getRootfsPath(),
                workingDir = "/root",
                command = "/bin/bash"
            )
            val testCmd = cmd.dropLast(1) + listOf("-c", "echo 'PRoot OK'")
            val pb = ProcessBuilder(testCmd)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            Log.i(TAG, "PRoot test output: $output (exit: $exitCode)")
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "PRoot test failed", e)
            false
        }
    }
}
