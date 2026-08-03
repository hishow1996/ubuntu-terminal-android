package com.linuxterminal.mobile.proot

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Manages the PRoot binary and Ubuntu rootfs setup.
 * Handles downloading, extracting, and launching the Ubuntu environment.
 */
class EnvironmentSetup(private val context: Context) {

    companion object {
        private const val TAG = "EnvironmentSetup"

        // Asset paths
        private const val BUNDLED_ROOTFS_ASSET = "ubuntu/ubuntu-rootfs.tar.gz"

        // Ubuntu rootfs download URLs (fallback for non-arm64 devices)
        private val UBUNTU_ROOTFS_URLS = mapOf(
            "arm64" to "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz",
            "armhf" to "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-armhf.tar.gz",
            "x86_64" to "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-amd64.tar.gz",
            "x86" to "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-i386.tar.gz"
        )
    }

    // Directories
    val dataDir: File = File(context.filesDir, "ubuntu")
    val rootfsDir: File = File(dataDir, "rootfs")
    val prootDir: File = File(dataDir, "proot")
    val prootBinary: File = File(prootDir, "proot")
    val prootLibDir: File = File(prootDir, "lib")
    val tmpDir: File = File(dataDir, "tmp")

    // Status
    var isInstalled: Boolean = false
        private set

    /** Get the device architecture */
    fun getArch(): String {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "armhf"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> "arm64"
        }
    }

    /** Check if PRoot binary is available */
    fun isPRootAvailable(): Boolean {
        // Check if we have a bundled binary in assets
        val arch = getArch()
        val assetName = "proot/${arch}_proot"
        try {
            context.assets.open(assetName).use { stream ->
                return true
            }
        } catch (e: IOException) {
            // Check if already extracted
            return prootBinary.exists() && prootBinary.canExecute() && prootLibDir.exists()
        }
    }

    /** Copy PRoot binary and libtalloc from assets to data directory */
    fun extractPRootBinary(): Boolean {
        if (prootBinary.exists() && prootBinary.canExecute() && prootLibDir.exists()) {
            return true
        }

        val arch = getArch()
        val assetName = "proot/${arch}_proot"

        return try {
            prootDir.mkdirs()
            prootBinary.parentFile?.mkdirs()
            context.assets.open(assetName).use { input ->
                FileOutputStream(prootBinary).use { output ->
                    input.copyTo(output)
                }
            }
            // Set executable permission
            prootBinary.setExecutable(true, true)
            Log.i(TAG, "PRoot binary extracted to ${prootBinary.absolutePath}")

            // Also extract libtalloc.so files
            prootLibDir.mkdirs()
            val libAssetPath = "proot/lib/$arch"
            val libFiles = context.assets.list(libAssetPath) ?: emptyArray()
            for (libFile in libFiles) {
                val libPath = "$libAssetPath/$libFile"
                val destFile = File(prootLibDir, libFile)
                context.assets.open(libPath).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Extracted library: ${destFile.name}")
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to extract PRoot from assets", e)
            // Try downloading from a URL as fallback
            false
        }
    }

    /** Check if Ubuntu rootfs is installed */
    fun isRootfsInstalled(): Boolean {
        return File(rootfsDir, "bin/bash").exists()
    }

    /** Get the rootfs download URL for the current architecture */
    fun getRootfsUrl(): String {
        val arch = getArch()
        return UBUNTU_ROOTFS_URLS[arch] ?: UBUNTU_ROOTFS_URLS["arm64"]!!
    }

    /** Get the rootfs download size estimate (for progress display) */
    fun getRootfsDownloadSizeEstimate(): Long {
        return 30L * 1024 * 1024 // ~30MB for ubuntu-base
    }

    /** Set up directories */
    fun setupDirectories() {
        dataDir.mkdirs()
        rootfsDir.mkdirs()
        prootDir.mkdirs()
        tmpDir.mkdirs()
    }

    /** Mark as installed */
    fun setInstalled() {
        isInstalled = true
    }

    /** Check if rootfs is bundled in assets (arm64 only) */
    fun isRootfsBundled(): Boolean {
        return try {
            context.assets.open(BUNDLED_ROOTFS_ASSET).use { it.available() > 0 }
        } catch (e: Exception) {
            false
        }
    }

    /** Get the InputStream for the bundled rootfs */
    fun openBundledRootfs(): java.io.InputStream {
        return context.assets.open(BUNDLED_ROOTFS_ASSET)
    }

    /** Get the rootfs download URL for the current architecture */
    fun getRootfsDownloadUrl(): String {
        val arch = getArch()
        return UBUNTU_ROOTFS_URLS[arch] ?: UBUNTU_ROOTFS_URLS["arm64"]!!
    }

    /** Get the bind mount directories for PRoot */
    fun getBindMounts(): List<String> {
        return listOf(
            "/dev",
            "/proc",
            "/sys",
            "/dev/urandom:/dev/random"
        )
    }

    /** Get the PRoot working directory (rootfs path) */
    fun getRootfsPath(): String = rootfsDir.absolutePath

    /** Get the PRoot tmp directory */
    fun getProotTmpDir(): String = tmpDir.absolutePath

    /** Clean up the environment (delete rootfs) */
    fun cleanup() {
        try {
            rootfsDir.deleteRecursively()
            prootBinary.delete()
            isInstalled = false
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        }
    }

    /** Get total size of the rootfs */
    fun getRootfsSize(): Long {
        return getDirSize(rootfsDir)
    }

    private fun getDirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
