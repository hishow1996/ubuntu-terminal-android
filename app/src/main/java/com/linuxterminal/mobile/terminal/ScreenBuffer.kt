package com.linuxterminal.mobile.terminal

/**
 * Represents a single cell in the terminal screen buffer.
 */
data class TerminalCell(
    var char: Char = ' ',
    var foregroundColor: Int = 7,  // Index into palette (default: white)
    var backgroundColor: Int = 0,  // Index into palette (default: black)
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var blink: Boolean = false,
    var reverse: Boolean = false,
    var strikethrough: Boolean = false
) {
    fun reset() {
        char = ' '
        foregroundColor = 7
        backgroundColor = 0
        bold = false
        italic = false
        underline = false
        blink = false
        reverse = false
        strikethrough = false
    }

    fun copyFrom(other: TerminalCell) {
        char = other.char
        foregroundColor = other.foregroundColor
        backgroundColor = other.backgroundColor
        bold = other.bold
        italic = other.italic
        underline = other.underline
        blink = other.blink
        reverse = other.reverse
        strikethrough = other.strikethrough
    }
}

/**
 * Terminal screen buffer with scrollback support.
 * Maintains a fixed-size grid of cells and manages scrolling.
 */
class ScreenBuffer(
    var columns: Int = 80,
    var rows: Int = 24,
    private val maxScrollback: Int = 5000
) {
    // Total lines = scrollback + visible rows
    private val buffer: Array<Array<TerminalCell>> = Array(maxScrollback + rows) {
        Array(columns) { TerminalCell() }
    }

    // The top line (in buffer coordinates) of the visible screen
    private var topLine: Int = 0

    // Current cursor position
    var cursorRow: Int = 0
    var cursorCol: Int = 0

    // Current text attributes (applied to new characters)
    var currentFg: Int = 7
    var currentBg: Int = 0
    var currentBold: Boolean = false
    var currentItalic: Boolean = false
    var currentUnderline: Boolean = false
    var currentBlink: Boolean = false
    var currentReverse: Boolean = false
    var currentStrikethrough: Boolean = false

    // Scroll region (top and bottom margins)
    var scrollTop: Int = 0
    var scrollBottom: Int = rows - 1

    // Saved cursor state (for DECSC/DECRC)
    private var savedRow: Int = 0
    private var savedCol: Int = 0

    /** Get the cell at screen position (row, col) */
    fun getCell(row: Int, col: Int): TerminalCell {
        val bufferRow = topLine + row
        if (bufferRow < 0 || bufferRow >= buffer.size) return TerminalCell()
        if (col < 0 || col >= columns) return TerminalCell()
        return buffer[bufferRow][col]
    }

    /** Set the character at the current cursor position */
    fun putChar(c: Char) {
        if (cursorCol >= columns) {
            cursorCol = 0
            cursorRow++
            if (cursorRow > scrollBottom) {
                scrollUp(1)
                cursorRow = scrollBottom
            }
        }
        val bufferRow = topLine + cursorRow
        if (bufferRow >= 0 && bufferRow < buffer.size && cursorCol >= 0 && cursorCol < columns) {
            val cell = buffer[bufferRow][cursorCol]
            cell.char = c
            cell.foregroundColor = currentFg
            cell.backgroundColor = currentBg
            cell.bold = currentBold
            cell.italic = currentItalic
            cell.underline = currentUnderline
            cell.blink = currentBlink
            cell.reverse = currentReverse
            cell.strikethrough = currentStrikethrough
        }
        cursorCol++
    }

    /** Move cursor to position */
    fun moveCursor(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, columns - 1)
    }

    /** Move cursor relative */
    fun moveCursorRelative(dRow: Int, dCol: Int) {
        cursorRow = (cursorRow + dRow).coerceIn(0, rows - 1)
        cursorCol = (cursorCol + dCol).coerceIn(0, columns - 1)
    }

    /** Save cursor state */
    fun saveCursor() {
        savedRow = cursorRow
        savedCol = cursorCol
    }

    /** Restore cursor state */
    fun restoreCursor() {
        cursorRow = savedRow
        cursorCol = savedCol
    }

    /** Erase from cursor to end of line */
    fun eraseToEndOfLine() {
        val bufferRow = topLine + cursorRow
        if (bufferRow in buffer.indices) {
            for (col in cursorCol until columns) {
                buffer[bufferRow][col].reset()
            }
        }
    }

    /** Erase from start of line to cursor */
    fun eraseToStartOfLine() {
        val bufferRow = topLine + cursorRow
        if (bufferRow in buffer.indices) {
            for (col in 0..cursorCol) {
                buffer[bufferRow][col].reset()
            }
        }
    }

    /** Erase entire line */
    fun eraseLine() {
        val bufferRow = topLine + cursorRow
        if (bufferRow in buffer.indices) {
            for (col in 0 until columns) {
                buffer[bufferRow][col].reset()
            }
        }
    }

    /** Erase from cursor to end of screen */
    fun eraseToEndOfScreen() {
        eraseToEndOfLine()
        val bufferRow = topLine + cursorRow
        for (row in cursorRow + 1 until rows) {
            if (topLine + row < buffer.size) {
                for (col in 0 until columns) {
                    buffer[topLine + row][col].reset()
                }
            }
        }
    }

    /** Erase from start of screen to cursor */
    fun eraseToStartOfScreen() {
        eraseToStartOfLine()
        for (row in 0 until cursorRow) {
            if (topLine + row < buffer.size) {
                for (col in 0 until columns) {
                    buffer[topLine + row][col].reset()
                }
            }
        }
    }

    /** Erase entire screen */
    fun eraseScreen() {
        for (row in 0 until rows) {
            if (topLine + row < buffer.size) {
                for (col in 0 until columns) {
                    buffer[topLine + row][col].reset()
                }
            }
        }
    }

    /** Scroll up by n lines (content moves up, blank lines appear at bottom) */
    fun scrollUp(n: Int = 1) {
        repeat(n) {
            if (topLine + scrollBottom + 1 < buffer.size) {
                // Only scroll within the scroll region
                val range = scrollTop..scrollBottom
                for (row in range) {
                    if (topLine + row + 1 < buffer.size) {
                        for (col in 0 until columns) {
                            buffer[topLine + row][col].copyFrom(buffer[topLine + row + 1][col])
                        }
                    }
                }
                // Clear bottom line of scroll region
                val bottomRow = topLine + scrollBottom
                if (bottomRow < buffer.size) {
                    for (col in 0 until columns) {
                        buffer[bottomRow][col].reset()
                    }
                }
            }
        }
    }

    /** Scroll down by n lines (content moves down, blank lines appear at top) */
    fun scrollDown(n: Int = 1) {
        repeat(n) {
            val range = scrollBottom downTo scrollTop + 1
            for (row in range) {
                if (topLine + row < buffer.size && topLine + row - 1 >= 0) {
                    for (col in 0 until columns) {
                        buffer[topLine + row][col].copyFrom(buffer[topLine + row - 1][col])
                    }
                }
            }
            // Clear top line of scroll region
            val topRow = topLine + scrollTop
            if (topRow in buffer.indices) {
                for (col in 0 until columns) {
                    buffer[topRow][col].reset()
                }
            }
        }
    }

    /** Line feed (move down one line, scroll if needed) */
    fun lineFeed() {
        cursorCol = 0
        cursorRow++
        if (cursorRow > scrollBottom) {
            scrollUp(1)
            cursorRow = scrollBottom
        }
    }

    /** Carriage return (move to column 0) */
    fun carriageReturn() {
        cursorCol = 0
    }

    /** Tab (move to next tab stop) */
    fun tab() {
        val nextTab = ((cursorCol / 8) + 1) * 8
        cursorCol = if (nextTab >= columns) columns - 1 else nextTab
    }

    /** Backspace (move left one column) */
    fun backspace() {
        if (cursorCol > 0) cursorCol--
    }

    /** Ring the bell (visual indication) */
    fun bell() {
        // Handled by the view
    }

    /** Set scroll region */
    fun setScrollRegion(top: Int, bottom: Int) {
        scrollTop = top.coerceIn(0, rows - 1)
        scrollBottom = bottom.coerceIn(0, rows - 1)
        cursorRow = 0
        cursorCol = 0
    }

    /** Reset all attributes to default */
    fun resetAttributes() {
        currentFg = 7
        currentBg = 0
        currentBold = false
        currentItalic = false
        currentUnderline = false
        currentBlink = false
        currentReverse = false
        currentStrikethrough = false
    }

    /** Resize the buffer */
    fun resize(newCols: Int, newRows: Int) {
        if (newCols == columns && newRows == rows) return
        columns = newCols
        rows = newRows
        scrollBottom = rows - 1
        if (cursorRow >= rows) cursorRow = rows - 1
        if (cursorCol >= columns) cursorCol = columns - 1
    }

    /** Get the total number of rows in the buffer (for rendering) */
    fun getTotalRows(): Int = rows

    /** Get the scroll offset */
    fun getTopLine(): Int = topLine
}
