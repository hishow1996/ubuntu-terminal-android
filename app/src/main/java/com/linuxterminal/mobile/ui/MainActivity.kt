package com.linuxterminal.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.linuxterminal.mobile.R
import com.linuxterminal.mobile.proot.EnvironmentSetup
import com.linuxterminal.mobile.proot.PRootRunner
import com.linuxterminal.mobile.proot.TerminalService
import com.linuxterminal.mobile.terminal.SpecialKey
import com.linuxterminal.mobile.terminal.TerminalEmulator
import com.linuxterminal.mobile.terminal.TerminalSession
import com.linuxterminal.mobile.terminal.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Main activity hosting the terminal view and special keyboard.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var emulator: TerminalEmulator
    private lateinit var session: TerminalSession
    private lateinit var envSetup: EnvironmentSetup
    private lateinit var prootRunner: PRootRunner

    private lateinit var titleText: TextView
    private lateinit var extraKeysBar: LinearLayout

    private val extraKeys = listOf(
        "ESC" to { emulator.sendKey(SpecialKey.ESCAPE) },
        "TAB" to { emulator.sendKey(SpecialKey.TAB) },
        "CTRL" to { /* handled specially */ },
        "ALT" to { /* handled specially */ },
        "↑" to { emulator.sendKey(SpecialKey.UP) },
        "↓" to { emulator.sendKey(SpecialKey.DOWN) },
        "←" to { emulator.sendKey(SpecialKey.LEFT) },
        "→" to { emulator.sendKey(SpecialKey.RIGHT) },
        "HOME" to { emulator.sendKey(SpecialKey.HOME) },
        "END" to { emulator.sendKey(SpecialKey.END) },
        "PGUP" to { emulator.sendKey(SpecialKey.PAGE_UP) },
        "PGDN" to { emulator.sendKey(SpecialKey.PAGE_DOWN) },
        "DEL" to { emulator.sendKey(SpecialKey.DELETE) },
        "BKSP" to { emulator.sendKey(SpecialKey.BACKSPACE) },
        "ENTER" to { emulator.sendKey(SpecialKey.ENTER) }
    )

    // Ctrl modifier state
    private var ctrlMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep screen on during terminal session
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        terminalView = findViewById(R.id.terminalView)
        titleText = findViewById(R.id.titleText)
        extraKeysBar = findViewById(R.id.extraKeysBar)

        envSetup = EnvironmentSetup(this)
        prootRunner = PRootRunner(this, envSetup)

        setupTerminal()
        setupExtraKeys()

        // Start terminal session
        startTerminalSession()
    }

    private fun setupTerminal() {
        val cols = 80
        val rows = 24
        emulator = TerminalEmulator(
            com.linuxterminal.mobile.terminal.ScreenBuffer(cols, rows),
            cols, rows
        )
        emulator.callback = object : TerminalEmulator.TerminalCallback {
            override fun onTitleChanged(title: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    titleText.text = title
                }
            }

            override fun onBell() {
                terminalView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }

            override fun onColorChanged() {
                terminalView.postInvalidate()
            }

            override fun onScreenChanged() {
                terminalView.postInvalidate()
            }

            override fun onOscCommand(code: Int, data: String) {
                // Handle OSC commands if needed
            }
        }
        terminalView.emulator = emulator

        terminalView.onSizeChanged = { cols, rows ->
            emulator.resize(cols, rows)
            // Send new terminal size to the process
            val sizeCmd = "\u001B[8;${rows};${cols}t"
            emulator.write(sizeCmd)
        }
    }

    private fun setupExtraKeys() {
        for ((label, action) in extraKeys) {
            val button = Button(this).apply {
                text = label
                textSize = 11f
                setAllCaps(false)
                setBackgroundResource(R.drawable.extra_key_bg)
                setTextColor(getColor(R.color.extra_key_text))
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(4, 4, 4, 4)
                    minWidth = (48 * resources.displayMetrics.density).toInt()
                    minHeight = (40 * resources.displayMetrics.density).toInt()
                }
                layoutParams = params
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (label == "CTRL") {
                        ctrlMode = !ctrlMode
                        isSelected = ctrlMode
                        if (ctrlMode) {
                            Toast.makeText(this@MainActivity, "Ctrl mode ON - press a key", Toast.LENGTH_SHORT).show()
                        }
                    } else if (ctrlMode) {
                        // Send Ctrl+key
                        val ch = when (label) {
                            "↑" -> 'e'; "↓" -> 'n'
                            "←" -> 'b'; "→" -> 'f'
                            "ENTER" -> 'j'; "BKSP" -> 'h'
                            else -> label.first().lowercaseChar()
                        }
                        emulator.sendCtrlKey(ch)
                        ctrlMode = false
                        // Reset ctrl button state
                        val ctrlBtn = extraKeysBar.findViewWithTag<Button>("CTRL")
                        ctrlBtn?.isSelected = false
                    } else {
                        action()
                    }
                }
                if (label == "CTRL") tag = "CTRL"
            }
            extraKeysBar.addView(button)
        }
    }

    private fun startTerminalSession() {
        if (!prootRunner.isReady()) {
            Toast.makeText(this, "Environment not ready. Please restart the app.", Toast.LENGTH_LONG).show()
            // Show a message in the terminal
            emulator.write("Ubuntu environment is not ready. Please restart the app.\r\n")
            emulator.write("If this persists, clear app data and try again.\r\n")
            return
        }

        session = TerminalSession(emulator)
        session.listener = object : TerminalSession.SessionListener {
            override fun onProcessStarted() {
                lifecycleScope.launch(Dispatchers.Main) {
                    titleText.text = "Ubuntu Terminal"
                    TerminalService.start(this@MainActivity)
                }
            }

            override fun onProcessExited(exitCode: Int) {
                lifecycleScope.launch(Dispatchers.Main) {
                    titleText.text = "Session ended (exit: $exitCode)"
                    TerminalService.stop(this@MainActivity)
                    emulator.write("\r\n[Process exited with code $exitCode]\r\n")
                }
            }

            override fun onOutput(data: ByteArray) {
                // Output already processed by emulator
            }

            override fun onError(message: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }

            override fun onTitleChanged(title: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    titleText.text = title
                }
            }
        }

        // Build and start PRoot command
        val command = prootRunner.buildInteractiveCommand()
        val env = prootRunner.getEnvironment()
        session.start(command, env, envSetup.rootfsDir.absolutePath)

        // Auto-show keyboard
        terminalView.showKeyboard()
    }

    override fun onResume() {
        super.onResume()
        terminalView.showKeyboard()
    }

    override fun onPause() {
        super.onPause()
        terminalView.hideKeyboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::session.isInitialized) {
            session.stop()
        }
        TerminalService.stop(this)
    }

    fun openSettings(view: View) {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
}
