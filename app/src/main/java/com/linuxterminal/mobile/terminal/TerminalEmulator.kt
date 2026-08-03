package com.linuxterminal.mobile.terminal

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * ANSI terminal emulator state machine.
 * Processes incoming byte stream and updates the screen buffer.
 *
 * Supports:
 * - UTF-8 multi-byte characters
 * - ANSI escape sequences (CSI)
 * - SGR (Select Graphic Rendition) colors
 * - Cursor movement and screen manipulation
 * - OSC (Operating System Command) - title setting
 * - DEC private modes (line wrap, cursor visibility, etc.)
 */
class TerminalEmulator(
    val buffer: ScreenBuffer,
    var columns: Int = 80,
    var rows: Int = 24
) {
    interface TerminalCallback {
        fun onTitleChanged(title: String)
        fun onBell()
        fun onColorChanged()
        fun onScreenChanged()
        fun onOscCommand(code: Int, data: String)
    }

    var callback: TerminalCallback? = null

    // Parser state machine
    private enum class State {
        NORMAL, ESCAPE, CSI, OSC, OSC_ESC, UTF8
    }

    private var state: State = State.NORMAL
    private val csiBuffer = StringBuilder()
    private val oscBuffer = StringBuilder()
    private var utf8Buffer = ByteArray(4)
    private var utf8Length = 0
    private var utf8Expected = 0

    // Terminal modes
    var cursorVisible: Boolean = true
    var applicationKeypad: Boolean = false
    var bracketedPaste: Boolean = false
    var alternateScreen: Boolean = false

    // Alternate screen buffer
    private val altBuffer: ScreenBuffer = ScreenBuffer(columns, rows)

    // Input queue (for keyboard input to send to process)
    val inputQueue: ConcurrentLinkedQueue<ByteArray> = ConcurrentLinkedQueue()

    /** Process incoming bytes from the shell process */
    @Synchronized
    fun processBytes(data: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) {
            processByte(data[i].toInt() and 0xFF)
        }
        callback?.onScreenChanged()
    }

    private fun processByte(byte: Int) {
        when (state) {
            State.NORMAL -> processNormal(byte)
            State.ESCAPE -> processEscape(byte)
            State.CSI -> processCSI(byte)
            State.OSC -> processOSC(byte)
            State.OSC_ESC -> processOSCEsc(byte)
            State.UTF8 -> processUTF8(byte)
        }
    }

    private fun processNormal(byte: Int) {
        when {
            byte == 0x1B -> state = State.ESCAPE // ESC
            byte == 0x07 -> { // BEL
                callback?.onBell()
            }
            byte == 0x08 -> buffer.backspace() // BS
            byte == 0x09 -> buffer.tab() // HT
            byte == 0x0A -> buffer.lineFeed() // LF
            byte == 0x0B -> buffer.lineFeed() // VT (treat as LF)
            byte == 0x0C -> buffer.lineFeed() // FF (treat as LF)
            byte == 0x0D -> buffer.carriageReturn() // CR
            byte < 0x80 -> buffer.putChar(byte.toChar()) // ASCII
            byte and 0xC0 == 0xC0 -> { // UTF-8 start byte
                startUTF8(byte)
            }
            else -> buffer.putChar(byte.toChar())
        }
    }

    private fun startUTF8(byte: Int) {
        utf8Length = 0
        utf8Buffer[utf8Length++] = byte.toByte()
        utf8Expected = when {
            byte and 0xE0 == 0xC0 -> 1
            byte and 0xF0 == 0xE0 -> 2
            byte and 0xF8 == 0xF0 -> 3
            else -> { buffer.putChar(byte.toChar()); return }
        }
        state = State.UTF8
    }

    private fun processUTF8(byte: Int) {
        if (byte and 0xC0 == 0x80) {
            utf8Buffer[utf8Length++] = byte.toByte()
            if (utf8Length >= utf8Expected + 1) {
                val str = String(utf8Buffer, 0, utf8Length, Charsets.UTF_8)
                if (str.isNotEmpty()) {
                    buffer.putChar(str[0])
                }
                state = State.NORMAL
            }
        } else {
            // Invalid UTF-8 sequence, revert
            state = State.NORMAL
            processNormal(byte)
        }
    }

    private fun processEscape(byte: Int) {
        when (byte.toChar()) {
            '[' -> {
                state = State.CSI
                csiBuffer.setLength(0)
            }
            ']' -> {
                state = State.OSC
                oscBuffer.setLength(0)
            }
            'M' -> { // Reverse line feed (RI)
                buffer.cursorRow--
                if (buffer.cursorRow < 0) buffer.cursorRow = 0
                state = State.NORMAL
            }
            'D' -> { // Index (IND) - line feed
                buffer.lineFeed()
                state = State.NORMAL
            }
            'E' -> { // Next line (NEL)
                buffer.carriageReturn()
                buffer.lineFeed()
                state = State.NORMAL
            }
            '7' -> { // DECSC - Save cursor
                buffer.saveCursor()
                state = State.NORMAL
            }
            '8' -> { // DECRC - Restore cursor
                buffer.restoreCursor()
                state = State.NORMAL
            }
            'c' -> { // RIS - Reset to initial state
                reset()
                state = State.NORMAL
            }
            '(' -> { // Designate G0 charset - skip next byte
                state = State.NORMAL
            }
            ')' -> { // Designate G1 charset - skip next byte
                state = State.NORMAL
            }
            '=' -> { // Application keypad mode
                applicationKeypad = true
                state = State.NORMAL
            }
            '>' -> { // Normal keypad mode
                applicationKeypad = false
                state = State.NORMAL
            }
            else -> state = State.NORMAL
        }
    }

    private fun processCSI(byte: Int) {
        val c = byte.toChar()
        if (c in '0'..'9' || c == ';' || c == '?' || c == '>' || c == '!' || c == ' ' || c == ':') {
            csiBuffer.append(c)
        } else {
            // Final character - execute the CSI command
            executeCSI(c, csiBuffer.toString())
            state = State.NORMAL
        }
    }

    private fun executeCSI(finalChar: Char, params: String) {
        val private = params.startsWith("?")
        val cleanParams = if (private) params.substring(1) else params

        val paramArray = parseParams(cleanParams)

        when (finalChar) {
            // Cursor movement
            'A' -> buffer.moveCursorRelative(-getInt(paramArray, 0, 1), 0) // CUU
            'B' -> buffer.moveCursorRelative(getInt(paramArray, 0, 1), 0) // CUD
            'C' -> buffer.moveCursorRelative(0, getInt(paramArray, 0, 1)) // CUF
            'D' -> buffer.moveCursorRelative(0, -getInt(paramArray, 0, 1)) // CUB
            'H', 'f' -> { // CUP - Cursor Position
                val row = getInt(paramArray, 0, 1) - 1
                val col = getInt(paramArray, 1, 1) - 1
                buffer.moveCursor(row, col)
            }
            'G' -> buffer.moveCursor(buffer.cursorRow, getInt(paramArray, 0, 1) - 1) // CHA
            'd' -> buffer.moveCursor(getInt(paramArray, 0, 1) - 1, buffer.cursorCol) // VPA
            'J' -> { // ED - Erase Display
                when (getInt(paramArray, 0, 0)) {
                    0 -> buffer.eraseToEndOfScreen()
                    1 -> buffer.eraseToStartOfScreen()
                    2 -> buffer.eraseScreen()
                    3 -> buffer.eraseScreen() // Erase scrollback
                }
            }
            'K' -> { // EL - Erase Line
                when (getInt(paramArray, 0, 0)) {
                    0 -> buffer.eraseToEndOfLine()
                    1 -> buffer.eraseToStartOfLine()
                    2 -> buffer.eraseLine()
                }
            }
            'S' -> buffer.scrollUp(getInt(paramArray, 0, 1)) // SU - Scroll Up
            'T' -> buffer.scrollDown(getInt(paramArray, 0, 1)) // SD - Scroll Down
            'L' -> { // IL - Insert Lines
                repeat(getInt(paramArray, 0, 1)) { buffer.scrollDown(1) }
            }
            'M' -> { // DL - Delete Lines
                repeat(getInt(paramArray, 0, 1)) { buffer.scrollUp(1) }
            }
            'P' -> { // DCH - Delete Characters
                val n = getInt(paramArray, 0, 1)
                val row = buffer.cursorRow
                val col = buffer.cursorCol
                for (c in col until columns - n) {
                    val src = buffer.getCell(row, c + n)
                    val dst = buffer.getCell(row, c)
                    dst.copyFrom(src)
                }
                for (c in columns - n until columns) {
                    buffer.getCell(row, c).reset()
                }
            }
            '@' -> { // ICH - Insert Characters
                val n = getInt(paramArray, 0, 1)
                val row = buffer.cursorRow
                val col = buffer.cursorCol
                for (c in columns - 1 downTo col + n) {
                    val src = buffer.getCell(row, c - n)
                    val dst = buffer.getCell(row, c)
                    dst.copyFrom(src)
                }
                for (c in col until col + n) {
                    buffer.getCell(row, c).reset()
                }
            }
            'X' -> { // ECH - Erase Characters
                val n = getInt(paramArray, 0, 1)
                val row = buffer.cursorRow
                for (c in buffer.cursorCol until minOf(buffer.cursorCol + n, columns)) {
                    buffer.getCell(row, c).reset()
                }
            }
            'm' -> processSGR(paramArray) // SGR - Select Graphic Rendition
            'r' -> { // DECSTBM - Set Scroll Region
                val top = getInt(paramArray, 0, 1) - 1
                val bottom = getInt(paramArray, 1, rows) - 1
                buffer.setScrollRegion(top, bottom)
            }
            'h' -> { // DEC Set Mode
                if (private) {
                    when (getInt(paramArray, 0, 0)) {
                        7 -> { /* Auto-wrap enabled by default */ }
                        25 -> cursorVisible = true
                        47, 1047 -> switchToAlternateScreen(false)
                        1048 -> buffer.saveCursor()
                        1049 -> switchToAlternateScreen(true)
                        2004 -> bracketedPaste = true
                    }
                }
            }
            'l' -> { // DEC Reset Mode
                if (private) {
                    when (getInt(paramArray, 0, 0)) {
                        25 -> cursorVisible = false
                        47, 1047 -> switchToNormalScreen(false)
                        1048 -> buffer.restoreCursor()
                        1049 -> switchToNormalScreen(true)
                        2004 -> bracketedPaste = false
                    }
                }
            }
            'n' -> { // DSR - Device Status Report
                // Respond with cursor position
                when (getInt(paramArray, 0, 0)) {
                    6 -> {
                        val response = "\u001B[${buffer.cursorRow + 1};${buffer.cursorCol + 1}R"
                        inputQueue.add(response.toByteArray())
                    }
                }
            }
            'c' -> { // DA - Device Attributes
                // Respond as VT100
                val response = "\u001B[?1;2c"
                inputQueue.add(response.toByteArray())
            }
            't' -> { /* Window manipulation - ignore */ }
        }
        callback?.onScreenChanged()
    }

    private fun processSGR(params: IntArray) {
        if (params.isEmpty()) {
            buffer.resetAttributes()
            return
        }

        var i = 0
        while (i < params.size) {
            when (params[i]) {
                0 -> buffer.resetAttributes()
                1 -> buffer.currentBold = true
                2 -> buffer.currentBold = false
                3 -> buffer.currentItalic = true
                4 -> buffer.currentUnderline = true
                5 -> buffer.currentBlink = true
                7 -> buffer.currentReverse = true
                9 -> buffer.currentStrikethrough = true
                21 -> buffer.currentUnderline = false
                22 -> { buffer.currentBold = false }
                23 -> buffer.currentItalic = false
                24 -> buffer.currentUnderline = false
                25 -> buffer.currentBlink = false
                27 -> buffer.currentReverse = false
                29 -> buffer.currentStrikethrough = false
                in 30..37 -> buffer.currentFg = params[i] - 30
                38 -> {
                    // Extended foreground color
                    if (i + 1 < params.size) {
                        val color = TerminalColors.parseColor(params, i + 1)
                        buffer.currentFg = color
                        when (params[i + 1]) {
                            2 -> i += 4 // 38;2;R;G;B
                            5 -> i += 2 // 38;5;N
                        }
                    }
                }
                39 -> buffer.currentFg = 7 // Default foreground
                in 40..47 -> buffer.currentBg = params[i] - 40
                48 -> {
                    if (i + 1 < params.size) {
                        val color = TerminalColors.parseColor(params, i + 1)
                        buffer.currentBg = color
                        when (params[i + 1]) {
                            2 -> i += 4
                            5 -> i += 2
                        }
                    }
                }
                49 -> buffer.currentBg = 0 // Default background
                in 90..97 -> buffer.currentFg = params[i] - 90 + 8 // Bright foreground
                in 100..107 -> buffer.currentBg = params[i] - 100 + 8 // Bright background
            }
            i++
        }
        callback?.onColorChanged()
    }

    private fun processOSC(byte: Int) {
        if (byte == 0x07) { // BEL terminates OSC
            executeOSC()
            state = State.NORMAL
        } else if (byte == 0x1B) {
            state = State.OSC_ESC
        } else {
            oscBuffer.appendCodePoint(byte)
        }
    }

    private fun processOSCEsc(byte: Int) {
        if (byte == '\\'.code) { // ST terminates OSC
            executeOSC()
            state = State.NORMAL
        } else {
            oscBuffer.appendCodePoint(0x1B)
            oscBuffer.appendCodePoint(byte)
            state = State.OSC
        }
    }

    private fun executeOSC() {
        val data = oscBuffer.toString()
        val semiIdx = data.indexOf(';')
        if (semiIdx >= 0) {
            val code = data.substring(0, semiIdx).toIntOrNull() ?: return
            val payload = data.substring(semiIdx + 1)
            when (code) {
                0, 2 -> callback?.onTitleChanged(payload) // Set window title
                else -> callback?.onOscCommand(code, payload)
            }
        }
    }

    private fun switchToAlternateScreen(saveCursor: Boolean) {
        if (alternateScreen) return
        alternateScreen = true
        if (saveCursor) buffer.saveCursor()
        // Swap to alternate buffer
        val main = buffer
        // We'll just save the state and clear
        buffer.eraseScreen()
        buffer.moveCursor(0, 0)
    }

    private fun switchToNormalScreen(restoreCursor: Boolean) {
        if (!alternateScreen) return
        alternateScreen = false
        buffer.eraseScreen()
        if (restoreCursor) buffer.restoreCursor()
    }

    private fun parseParams(s: String): IntArray {
        if (s.isEmpty()) return IntArray(0)
        return s.split(";").map {
            it.toIntOrNull() ?: 0
        }.toIntArray()
    }

    private fun getInt(params: IntArray, index: Int, default: Int): Int {
        return if (index < params.size && params[index] != 0) params[index] else default
    }

    /** Reset terminal to initial state */
    fun reset() {
        buffer.eraseScreen()
        buffer.moveCursor(0, 0)
        buffer.resetAttributes()
        buffer.setScrollRegion(0, rows - 1)
        cursorVisible = true
        applicationKeypad = false
        bracketedPaste = false
        alternateScreen = false
        state = State.NORMAL
    }

    /** Resize terminal */
    fun resize(cols: Int, rws: Int) {
        columns = cols
        rows = rws
        buffer.resize(cols, rws)
    }

    /** Write input to be sent to the process */
    fun write(data: ByteArray) {
        inputQueue.add(data)
    }

    /** Write string input */
    fun write(text: String) {
        inputQueue.add(text.toByteArray())
    }

    /** Send special key */
    fun sendKey(key: SpecialKey) {
        val seq = when (key) {
            SpecialKey.UP -> "\u001B[A"
            SpecialKey.DOWN -> "\u001B[B"
            SpecialKey.RIGHT -> "\u001B[C"
            SpecialKey.LEFT -> "\u001B[D"
            SpecialKey.HOME -> "\u001B[H"
            SpecialKey.END -> "\u001B[F"
            SpecialKey.PAGE_UP -> "\u001B[5~"
            SpecialKey.PAGE_DOWN -> "\u001B[6~"
            SpecialKey.INSERT -> "\u001B[2~"
            SpecialKey.DELETE -> "\u001B[3~"
            SpecialKey.F1 -> "\u001BOP"
            SpecialKey.F2 -> "\u001BOQ"
            SpecialKey.F3 -> "\u001BOR"
            SpecialKey.F4 -> "\u001BOS"
            SpecialKey.F5 -> "\u001B[15~"
            SpecialKey.F6 -> "\u001B[17~"
            SpecialKey.F7 -> "\u001B[18~"
            SpecialKey.F8 -> "\u001B[19~"
            SpecialKey.F9 -> "\u001B[20~"
            SpecialKey.F10 -> "\u001B[21~"
            SpecialKey.F11 -> "\u001B[23~"
            SpecialKey.F12 -> "\u001B[24~"
            SpecialKey.TAB -> "\t"
            SpecialKey.ENTER -> "\r"
            SpecialKey.BACKSPACE -> "\u007F"
            SpecialKey.ESCAPE -> "\u001B"
        }
        inputQueue.add(seq.toByteArray())
    }

    /** Send Ctrl+key combination */
    fun sendCtrlKey(c: Char) {
        val code = when (c.lowercaseChar()) {
            'a' -> 0x01; 'b' -> 0x02; 'c' -> 0x03; 'd' -> 0x04
            'e' -> 0x05; 'f' -> 0x06; 'g' -> 0x07; 'h' -> 0x08
            'i' -> 0x09; 'j' -> 0x0A; 'k' -> 0x0B; 'l' -> 0x0C
            'm' -> 0x0D; 'n' -> 0x0E; 'o' -> 0x0F; 'p' -> 0x10
            'q' -> 0x11; 'r' -> 0x12; 's' -> 0x13; 't' -> 0x14
            'u' -> 0x15; 'v' -> 0x16; 'w' -> 0x17; 'x' -> 0x18
            'y' -> 0x19; 'z' -> 0x1A
            '[' -> 0x1B; '\\' -> 0x1C; ']' -> 0x1D; '^' -> 0x1E
            '_' -> 0x1F; ' ' -> 0x00
            else -> return
        }
        inputQueue.add(byteArrayOf(code.toByte()))
    }
}

enum class SpecialKey {
    UP, DOWN, LEFT, RIGHT, HOME, END,
    PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    TAB, ENTER, BACKSPACE, ESCAPE
}
