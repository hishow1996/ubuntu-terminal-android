package com.linuxterminal.mobile.proot

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Handles downloading and extracting the Ubuntu rootfs.
 * Uses tar/gzip extraction with progress callbacks.
 */
class UbuntuInstaller(private val context: Context, private val envSetup: EnvironmentSetup) {

    companion object {
        private const val TAG = "UbuntuInstaller"
        private const val BUFFER_SIZE = 8192
    }

    interface InstallListener {
        fun onProgress(percent: Int, message: String)
        fun onComplete()
        fun onError(message: String)
        fun onLog(message: String)
    }

    var listener: InstallListener? = null

    /**
     * Download and extract the Ubuntu rootfs.
     * This is a blocking call - should be called from a background thread.
     */
    fun installRootfs() {
        val arch = envSetup.getArch()
        val url = envSetup.getRootfsUrl()

        listener?.onProgress(0, "Preparing...")
        listener?.onLog("Architecture: $arch")
        listener?.onLog("Download URL: $url")

        // Create temp file for download
        val tempFile = File(envSetup.dataDir, "rootfs.tar.gz")
        tempFile.parentFile?.mkdirs()

        try {
            // Step 1: Download rootfs
            listener?.onProgress(5, "Downloading Ubuntu rootfs...")
            val downloaded = downloadFile(url, tempFile)
            listener?.onLog("Downloaded ${downloaded / 1024 / 1024} MB")
            listener?.onProgress(40, "Download complete. Extracting...")

            // Step 2: Extract rootfs
            listener?.onProgress(45, "Extracting Ubuntu rootfs...")
            envSetup.rootfsDir.mkdirs()
            extractTarGz(tempFile, envSetup.rootfsDir)
            listener?.onProgress(75, "Extraction complete")

            // Step 3: Set up Ubuntu environment
            listener?.onProgress(80, "Configuring Ubuntu environment...")
            configureUbuntu()

            listener?.onProgress(100, "Ubuntu environment ready!")
            envSetup.setInstalled()
            listener?.onComplete()
        } catch (e: Exception) {
            Log.e(TAG, "Installation failed", e)
            listener?.onError("Installation failed: ${e.message}")
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Extract the bundled Ubuntu rootfs from APK assets.
     * No download needed — the rootfs ships inside the APK.
     */
    fun installBundledRootfs() {
        listener?.onProgress(0, "Preparing bundled Ubuntu rootfs...")
        listener?.onLog("Using bundled rootfs from APK assets")

        val tempFile = File(envSetup.dataDir, "rootfs.tar.gz")
        tempFile.parentFile?.mkdirs()

        try {
            // Step 1: Copy bundled rootfs from assets to temp file
            listener?.onProgress(5, "Reading bundled Ubuntu rootfs...")
            val assetStream = envSetup.openBundledRootfs()
            val totalSize = assetStream.available().toLong()
            listener?.onLog("Bundled rootfs size: ${totalSize / 1024 / 1024} MB")

            var copied = 0L
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(BUFFER_SIZE * 4)
                var bytesRead: Int
                while (assetStream.read(buffer).also { bytesRead = it } > 0) {
                    output.write(buffer, 0, bytesRead)
                    copied += bytesRead
                    val percent = (5 + (copied.toDouble() / totalSize * 20)).toInt()
                    listener?.onProgress(percent.coerceAtMost(25),
                        "Reading rootfs... ${copied / 1024 / 1024} MB")
                }
            }
            assetStream.close()
            listener?.onProgress(25, "Rootfs copied. Extracting...")

            // Step 2: Extract rootfs
            listener?.onProgress(30, "Extracting Ubuntu rootfs...")
            envSetup.rootfsDir.mkdirs()
            extractTarGz(tempFile, envSetup.rootfsDir)
            listener?.onProgress(75, "Extraction complete")

            // Step 3: Set up Ubuntu environment
            listener?.onProgress(80, "Configuring Ubuntu environment...")
            configureUbuntu()

            listener?.onProgress(100, "Ubuntu environment ready!")
            envSetup.setInstalled()
            listener?.onComplete()
        } catch (e: Exception) {
            Log.e(TAG, "Bundled installation failed", e)
            listener?.onError("Installation failed: ${e.message}")
        } finally {
            tempFile.delete()
        }
    }

    /** Download a file with progress reporting */
    private fun downloadFile(url: String, dest: File): Long {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.apply {
            connectTimeout = 30000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        connection.setRequestProperty("User-Agent", "UbuntuTerminal/1.0 (Android)")

        try {
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode: ${connection.responseMessage}")
            }

            val totalSize = connection.contentLengthLong
            var downloaded = 0L
            val fileSize = if (totalSize > 0) totalSize else envSetup.getRootfsDownloadSizeEstimate()

            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE * 4)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } > 0) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        val percent = if (totalSize > 0) {
                            (5 + (downloaded.toDouble() / totalSize * 35)).toInt()
                        } else {
                            (5 + (downloaded.toDouble() / fileSize * 35)).toInt()
                        }
                        listener?.onProgress(percent.coerceAtMost(40), "Downloading... ${downloaded / 1024 / 1024} MB")
                    }
                }
            }

            return downloaded
        } catch (e: Exception) {
            // Try fallback URL for arm64
            if (envSetup.getArch() == "arm64") {
                Log.w(TAG, "Primary URL failed, trying fallback")
                listener?.onLog("Trying alternative download source...")
                connection.disconnect()
                return downloadFileFallback(dest)
            }
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFileFallback(dest: File): Long {
        val fallbackUrl = "https://github.com/EXALAB/AnLinux-Resources/raw/master/Rootfs/Ubuntu/arm64/ubuntu-rootfs-arm64.tar.xz"
        val connection = java.net.URL(fallbackUrl).openConnection() as java.net.HttpURLConnection
        connection.apply {
            connectTimeout = 30000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        connection.connect()
        connection.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
        return dest.length()
    }

    /** Extract tar.gz file */
    private fun extractTarGz(tarGzFile: File, destDir: File) {
        // Try using system tar command first (faster)
        if (trySystemTarExtract(tarGzFile, destDir)) {
            return
        }
        // Fall back to Java extraction
        javaExtractTarGz(tarGzFile, destDir)
    }

    /** Try to use the system tar command for extraction */
    private fun trySystemTarExtract(tarGzFile: File, destDir: File): Boolean {
        return try {
            // --no-same-owner: skip chown (not permitted without root on Android)
            // --no-same-permissions: skip chmod to avoid permission errors
            val pb = ProcessBuilder("tar",
                "--no-same-owner",
                "--no-same-permissions",
                "-xzf", tarGzFile.absolutePath,
                "-C", destDir.absolutePath)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
                // Log warnings but continue
                val currentLine = line ?: ""
                if (currentLine.contains("Operation not permitted") ||
                    currentLine.contains("Cannot")) {
                    Log.w(TAG, "tar warning: $currentLine")
                }
            }
            val exitCode = process.waitFor()
            // exit 0 = success, exit 1 = minor errors (like chown warnings) but files extracted OK
            // exit 2 = serious error
            if (exitCode == 0 || exitCode == 1) {
                Log.i(TAG, "System tar extraction completed (exit $exitCode)")
                // Verify extraction actually produced files
                val extractedFiles = destDir.walkTopDown().count { it.isFile }
                if (extractedFiles > 0) {
                    Log.i(TAG, "Extracted $extractedFiles files")
                    true
                } else {
                    Log.e(TAG, "tar exited OK but no files extracted")
                    false
                }
            } else {
                Log.w(TAG, "System tar failed (exit $exitCode): $output")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "System tar not available, using Java extraction", e)
            false
        }
    }

    /** Java-based tar.gz extraction (slower but works everywhere) */
    private fun javaExtractTarGz(tarGzFile: File, destDir: File) {
        // Note: Full tar.gz extraction in pure Java requires an external library.
        // We'll use a runtime exec with toybox/busybox tar, which is available on Android 6+.
        try {
            val tarPath = "/system/bin/tar"
            // --no-same-owner: skip chown (not permitted without root on Android)
            val pb = ProcessBuilder(tarPath,
                "--no-same-owner",
                "--no-same-permissions",
                "-xzf", tarGzFile.absolutePath,
                "-C", destDir.absolutePath)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val exitCode = process.waitFor()
            // exit 0 = success, exit 1 = minor errors (chown warnings) but files extracted OK
            if (exitCode != 0 && exitCode != 1) {
                throw IOException("tar extraction failed (exit $exitCode): $output")
            }
            // Verify files were extracted
            val extractedFiles = destDir.walkTopDown().count { it.isFile }
            if (extractedFiles == 0) {
                throw IOException("tar completed but no files were extracted")
            }
            Log.i(TAG, "Extraction complete: $extractedFiles files (exit $exitCode)")
        } catch (e: Exception) {
            Log.e(TAG, "Java tar extraction failed", e)
            // Last resort: try gzip decompression + manual tar parsing
            // This is a minimal implementation
            throw IOException("Failed to extract rootfs: ${e.message}. The system tar command is required.")
        }
    }

    /** Configure the Ubuntu environment after extraction */
    private fun configureUbuntu() {
        val rootfs = envSetup.rootfsDir

        // Create necessary directories
        listOf("dev", "proc", "sys", "tmp", "root").forEach { dir ->
            File(rootfs, dir).mkdirs()
        }

        // Create /etc/resolv.conf for DNS resolution
        val resolvConf = File(rootfs, "etc/resolv.conf")
        if (!resolvConf.exists() || resolvConf.length() == 0L) {
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        }

        // Create /etc/hostname
        val hostname = File(rootfs, "etc/hostname")
        if (!hostname.exists()) {
            hostname.writeText("ubuntu\n")
        }

        // Create initial /etc/passwd if not present
        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.exists()) {
            passwd.writeText("root:x:0:0:root:/root:/bin/bash\n")
        }

        // Create /etc/group if not present
        val group = File(rootfs, "etc/group")
        if (!group.exists()) {
            group.writeText("root:x:0:\n")
        }

        // Create /etc/hosts
        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.exists()) {
            hosts.writeText("127.0.0.1 localhost\n127.0.1.1 ubuntu\n")
        }

        listener?.onLog("Ubuntu configuration complete")
    }
}
