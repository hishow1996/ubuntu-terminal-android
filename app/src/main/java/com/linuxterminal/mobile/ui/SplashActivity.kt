package com.linuxterminal.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.linuxterminal.mobile.R
import com.linuxterminal.mobile.proot.EnvironmentSetup
import com.linuxterminal.mobile.proot.PRootRunner
import com.linuxterminal.mobile.proot.UbuntuInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Splash screen that handles first-time Ubuntu setup.
 * Shows progress while downloading and extracting rootfs.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var envSetup: EnvironmentSetup
    private lateinit var installer: UbuntuInstaller
    private lateinit var prootRunner: PRootRunner

    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var logText: TextView
    private lateinit var subtitleText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Full screen immersive
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)

        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        logText = findViewById(R.id.logText)
        subtitleText = findViewById(R.id.subtitleText)

        envSetup = EnvironmentSetup(this)
        installer = UbuntuInstaller(this, envSetup)
        prootRunner = PRootRunner(this, envSetup)

        envSetup.setupDirectories()

        startSetup()
    }

    private fun startSetup() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Step 1: ALWAYS extract PRoot binary + .so files from assets.
                // No caching — ensures new .so files (libandroid-shmem etc.) are
                // always extracted even after app update.
                updateProgress(5, "Setting up PRoot and libraries...")
                val extracted = envSetup.extractPRootBinary()
                if (!extracted) {
                    withContext(Dispatchers.Main) {
                        logText.text = "Note: PRoot not bundled, trying download..."
                    }
                    downloadPRoot()
                }

                // Step 2: Check if Ubuntu is installed
                if (!envSetup.isRootfsInstalled()) {
                    // Need to install Ubuntu rootfs
                    installer.listener = object : UbuntuInstaller.InstallListener {
                        override fun onProgress(percent: Int, message: String) {
                            lifecycleScope.launch(Dispatchers.Main) {
                                progressBar.progress = percent
                                progressText.text = message
                            }
                        }

                        override fun onComplete() {
                            // Continue to main activity
                            launchMain()
                        }

                        override fun onError(message: String) {
                            lifecycleScope.launch(Dispatchers.Main) {
                                progressText.text = "Error: $message"
                                logText.text = "Installation failed. Tap to retry."
                                logText.setOnClickListener {
                                    recreate()
                                }
                            }
                        }

                        override fun onLog(message: String) {
                            lifecycleScope.launch(Dispatchers.Main) {
                                logText.append("$message\n")
                            }
                        }
                    }
                    // Use bundled rootfs if available (arm64), otherwise download
                    if (envSetup.isRootfsBundled()) {
                        installer.installBundledRootfs()
                    } else {
                        installer.installRootfs()
                    }
                } else {
                    // Already installed, go to main
                    updateProgress(100, "Ready!")
                    Thread.sleep(500)
                    launchMain()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressText.text = "Error: ${e.message}"
                    logText.text = "Setup failed. Tap to retry."
                    logText.setOnClickListener { recreate() }
                }
            }
        }
    }

    private fun downloadPRoot() {
        // If PRoot is not bundled in assets, try to download from Termux
        val arch = envSetup.getArch()
        val termuxArch = when (arch) {
            "arm64" -> "aarch64"
            "armhf" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "i686"
            else -> "aarch64"
        }

        try {
            // Download Packages index to find proot URL
            val packagesUrl = "https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-$termuxArch/Packages.gz"
            val conn = java.net.URL(packagesUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.connect()

            if (conn.responseCode == 200) {
                val gzInput = java.util.zip.GZIPInputStream(conn.inputStream)
                val packages = gzInput.bufferedReader().readText()
                gzInput.close()

                // Parse to find proot package
                val prootSection = packages.substringAfter("Package: proot\n", "")
                    .substringBefore("\n\n")
                val filename = prootSection.substringAfter("Filename: ", "")
                    .substringBefore("\n")

                if (filename.isNotEmpty()) {
                    val debUrl = "https://packages.termux.dev/apt/termux-main/$filename"
                    downloadAndExtractDeb(debUrl)
                    return
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            // Fallback: try direct download from known proot builds
            try {
                val debUrl = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.0-7_${termuxArch}.deb"
                downloadAndExtractDeb(debUrl)
                return
            } catch (e2: Exception) {
                // Will fail later when trying to use PRoot
            }
        }
    }

    private fun downloadAndExtractDeb(url: String) {
        try {
            val debFile = java.io.File(envSetup.dataDir, "proot.deb")
            java.net.URL(url).openConnection().let { conn ->
                conn as java.net.HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 30000
                conn.connect()
                if (conn.responseCode == 200) {
                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(debFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    extractDeb(debFile)
                }
            }
        } catch (e: Exception) {
            throw e
        }
    }

    private fun extractDeb(debFile: java.io.File) {
        // .deb files are ar archives containing data.tar.xz or data.tar.gz
        // Use ar and tar commands to extract
        val tmpDir = java.io.File(envSetup.dataDir, "deb_extract")
        tmpDir.mkdirs()

        // Try using system ar command
        try {
            val pb = ProcessBuilder("ar", "x", debFile.absolutePath)
            pb.directory(tmpDir)
            pb.redirectErrorStream(true)
            val process = pb.start()
            process.waitFor()

            // Find data.tar.* and extract
            val dataFile = tmpDir.listFiles()?.find { it.name.startsWith("data.tar") }
            if (dataFile != null) {
                val pb2 = if (dataFile.name.endsWith(".xz")) {
                    ProcessBuilder("tar", "xJf", dataFile.absolutePath, "-C", tmpDir.absolutePath)
                } else if (dataFile.name.endsWith(".gz")) {
                    ProcessBuilder("tar", "xzf", dataFile.absolutePath, "-C", tmpDir.absolutePath)
                } else if (dataFile.name.endsWith(".zst")) {
                    ProcessBuilder("tar", "--zstd", "-xf", dataFile.absolutePath, "-C", tmpDir.absolutePath)
                } else {
                    ProcessBuilder("tar", "xf", dataFile.absolutePath, "-C", tmpDir.absolutePath)
                }
                pb2.redirectErrorStream(true)
                val process2 = pb2.start()
                process2.waitFor()

                // Find the proot binary
                val prootInDeb = java.io.File(tmpDir, "data/data/com.termux/files/usr/bin/proot")
                if (prootInDeb.exists()) {
                    envSetup.prootDir.mkdirs()
                    prootInDeb.copyTo(envSetup.prootBinary, overwrite = true)
                    envSetup.prootBinary.setExecutable(true, true)
                }
            }
            tmpDir.deleteRecursively()
        } catch (e: Exception) {
            tmpDir.deleteRecursively()
            throw e
        }
    }

    private fun updateProgress(percent: Int, message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            progressBar.progress = percent
            progressText.text = message
        }
    }

    private fun launchMain() {
        lifecycleScope.launch(Dispatchers.Main) {
            val intent = Intent(this@SplashActivity, MainActivity::class.java)
            startActivity(intent)
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
