package com.linuxterminal.mobile.proot

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Manages the Ubuntu environment setup using PRoot.
 * Uses jniLibs for native binaries (Android auto-extracts to nativeLibraryDir).
 * Uses symlinks to create correctly-named library files in a writable directory.
 *
 * Architecture based on OnecodeTerminal.
 */
class EnvironmentSetup(private val context: Context) {

    companion object {
        private const val TAG = "EnvironmentSetup"
        private const val UBUNTU_FILENAME = "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
        private const val UBUNTU_NAME = "ubuntu-noble-aarch64"
    }

    // Directories
    val filesDir: File = context.filesDir
    val usrDir: File = File(filesDir, "usr")
    val binDir: File = File(usrDir, "bin")
    val tmpDir: File = File(filesDir, "tmp")
    val nativeLibDir: String = context.applicationInfo.nativeLibraryDir

    // Rootfs paths
    val prootDistroPath: String = "${usrDir.absolutePath}/var/lib/proot-distro"
    val ubuntuPath: String = "$prootDistroPath/installed-rootfs/ubuntu"

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

    /** Check if rootfs is bundled in assets */
    fun isRootfsBundled(): Boolean {
        return try {
            val files = context.assets.list("") ?: return false
            files.any { it == UBUNTU_FILENAME }
        } catch (e: Exception) {
            false
        }
    }

    /** Check if rootfs is already extracted */
    fun isRootfsInstalled(): Boolean {
        val okFile = File(ubuntuPath, ".onecode_installed_ok")
        return okFile.exists()
    }

    /** Get the rootfs tarball path in assets */
    fun getRootfsAssetName(): String = UBUNTU_FILENAME

    /** Get the rootfs tarball path on device */
    fun getRootfsTarballPath(): String = "${filesDir.absolutePath}/$UBUNTU_FILENAME"

    /** Create necessary directories */
    fun createDirectories() {
        usrDir.mkdirs()
        binDir.mkdirs()
        tmpDir.mkdirs()
        File(prootDistroPath).mkdirs()
    }

    /**
     * Link native libraries from nativeLibDir to binDir using symlinks.
     * Android extracts .so files from jniLibs to nativeLibDir automatically.
     * We create symlinks with correct names so the linker can find them.
     */
    fun linkNativeLibs() {
        Log.d(TAG, "Linking native libraries from: $nativeLibDir")
        val nativeLibDirFile = File(nativeLibDir)
        if (!nativeLibDirFile.exists() || !nativeLibDirFile.isDirectory) {
            Log.e(TAG, "Native library directory not found!")
            return
        }

        nativeLibDirFile.listFiles()?.forEach { file ->
            Log.d(TAG, "  - ${file.name} (${file.length()} bytes)")
        }

        val busybox = File(binDir, "busybox")
        val busyboxSo = File(nativeLibDir, "libbusybox.so")

        // Link busybox first
        if (busyboxSo.exists()) {
            try {
                Files.deleteIfExists(busybox.toPath())
                busyboxSo.setExecutable(true, false)
                Files.createSymbolicLink(busybox.toPath(), busyboxSo.toPath())
                Log.d(TAG, "Created busybox symlink")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create busybox link", e)
            }
        }

        // Create busybox applet symlinks
        val applets = listOf(
            "awk", "ash", "basename", "bzip2", "cp", "chmod", "cut", "cat",
            "du", "dd", "find", "grep", "gzip", "head", "id", "mkdir",
            "realpath", "rm", "sed", "stat", "sh", "tr", "tar", "uname",
            "xargs", "xz", "ln", "ls", "mv", "touch"
        )
        for (applet in applets) {
            val link = File(binDir, applet)
            try {
                Files.deleteIfExists(link.toPath())
                Files.createSymbolicLink(link.toPath(), Paths.get("busybox"))
            } catch (e: Exception) {
                // Ignore individual failures
            }
        }

        // Link other native libraries
        val libraries = mapOf(
            "libproot.so" to "proot",
            "libloader.so" to "loader",
            "liblibtalloc.so.2.so" to "libtalloc.so.2",
            "libbash.so" to "bash",
            "libsudo.so" to "sudo"
        )

        libraries.forEach { (libName, linkName) ->
            val libFile = File(nativeLibDir, libName)
            val linkFile = File(binDir, linkName)

            if (!libFile.exists()) {
                Log.w(TAG, "Native library not found: $libName")
                return@forEach
            }

            try {
                Files.deleteIfExists(linkFile.toPath())
                libFile.setExecutable(true, false)
                Files.createSymbolicLink(linkFile.toPath(), libFile.toPath())
                Log.d(TAG, "Created $linkName -> $libName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create $linkName link", e)
            }
        }
    }

    /** Extract the bundled rootfs tarball from assets to filesDir */
    fun extractRootfsTarball() {
        val assetFile = File(filesDir, UBUNTU_FILENAME)
        if (!assetFile.exists()) {
            Log.d(TAG, "Extracting $UBUNTU_FILENAME from assets...")
            context.assets.open(UBUNTU_FILENAME).use { input ->
                assetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Rootfs tarball extracted: ${assetFile.length()} bytes")
        }
    }

    /** Mark as installed */
    fun setInstalled() {
        isInstalled = true
    }

    /** Get the PRoot tmp directory */
    fun getProotTmpDir(): String = tmpDir.absolutePath

    /** Clean up */
    fun cleanup() {
        try {
            File(ubuntuPath).deleteRecursively()
            File(filesDir, UBUNTU_FILENAME).delete()
            isInstalled = false
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        }
    }
}
