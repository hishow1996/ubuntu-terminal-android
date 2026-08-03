package com.linuxterminal.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.linuxterminal.mobile.R
import com.linuxterminal.mobile.proot.EnvironmentSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Splash screen that handles first-time setup:
 * 1. Create directories
 * 2. Link native libraries (PRoot, busybox, etc. from jniLibs)
 * 3. Extract rootfs tarball from assets (if not already done)
 * 4. Launch MainActivity
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var envSetup: EnvironmentSetup
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)

        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)

        envSetup = EnvironmentSetup(this)

        startSetup()
    }

    private fun startSetup() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Step 1: Create directories
                updateProgress(5, "Creating directories...")
                envSetup.createDirectories()

                // Step 2: Link native libraries from jniLibs
                updateProgress(10, "Linking native libraries...")
                envSetup.linkNativeLibs()

                // Step 3: Extract rootfs tarball from assets if needed
                if (!envSetup.isRootfsInstalled()) {
                    updateProgress(20, "Extracting Ubuntu rootfs from assets...")
                    envSetup.extractRootfsTarball()
                    updateProgress(100, "Ready!")
                } else {
                    updateProgress(100, "Ready!")
                }

                Thread.sleep(500)
                launchMain()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressText.text = "Error: ${e.message}"
                }
            }
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
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
