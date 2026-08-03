package com.linuxterminal.mobile.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Scroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Custom View that renders the terminal screen.
 * Handles touch input, gestures, and software keyboard.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var emulator: TerminalEmulator? = null
        set(value) {
            field = value
            value?.let { updateFontSize() }
            postInvalidate()
        }

    // Rendering settings
    private var fontSize: Float = 14f // sp
    private var fontScale: Float = 1.0f
    private val density: Float = resources.displayMetrics.density

    // Computed metrics
    private var charWidth: Float = 0f
    private var charHeight: Float = 0f
    private var ascent: Float = 0f
    private var descent: Float = 0f
    private var paddingLeft: Float = 0f
    private var paddingTop: Float = 0f
    private var paddingRight: Float = 0f
    private var paddingBottom: Float = 0f

    // Paint objects (pre-allocated for performance)
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val cursorPaint = Paint()

    // Scrolling
    private val scroller: Scroller = Scroller(context)
    private var scrollOffset: Int = 0 // Lines scrolled back
    private var maxScrollOffset: Int = 0
    private val gestureDetector: GestureDetector

    // Cursor blink
    private var cursorVisible: Boolean = true
    private var lastBlinkTime: Long = 0
    private val blinkInterval: Long = 500

    // Theme colors
    var backgroundColor: Int = 0xFF1E1E2E.toInt()
    var foregroundColor: Int = 0xFFD4D4D4.toInt()
    var cursorColor: Int = 0xFFE95420.toInt() // Ubuntu orange
    var selectionColor: Int = 0xFF264F78.toInt()

    // Font
    var typeface: Typeface = Typeface.MONOSPACE
        set(value) { field = value; updateFontSize() }

    // Callbacks
    var onSizeChanged: ((Int, Int) -> Unit)? = null
    var onSpecialKeyPressed: ((SpecialKey) -> Unit)? = null

    // Selection
    private var selecting: Boolean = false
    private var selStartRow: Int = -1
    private var selStartCol: Int = -1
    private var selEndRow: Int = -1
    private var selEndCol: Int = -1

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(LAYER_TYPE_HARDWARE, null)

        fgPaint.typeface = typeface
        bgPaint.style = Paint.Style.FILL
        cursorPaint.style = Paint.Style.FILL
        cursorPaint.color = cursorColor

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                if (abs(distanceY) > abs(distanceX)) {
                    val linesScrolled = (distanceY / charHeight).toInt()
                    scrollOffset = (scrollOffset + linesScrolled).coerceIn(0, maxScrollOffset)
                    postInvalidate()
                    return true
                }
                return false
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(this@TerminalView, 0)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // Start text selection
                val (row, col) = posToCell(e.x, e.y)
                selecting = true
                selStartRow = row
                selStartCol = col
                selEndRow = row
                selEndCol = col
                postInvalidate()
            }
        })
    }

    fun setFontSize(sp: Float) {
        fontSize = sp
        updateFontSize()
        requestLayout()
        postInvalidate()
    }

    private fun updateFontSize() {
        val px = fontSize * density * fontScale
        fgPaint.textSize = px
        val metrics = fgPaint.fontMetrics
        ascent = metrics.ascent
        descent = metrics.descent
        charHeight = metrics.descent - metrics.ascent + metrics.leading
        // Measure monospace character width
        charWidth = fgPaint.measureText("M")
        // Add small padding
        charWidth = max(charWidth, px * 0.6f)
        charHeight = max(charHeight, px * 1.2f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        updateFontSize()

        val desiredWidth = (charWidth * 80 + paddingLeft + paddingRight).toInt()
        val desiredHeight = (charHeight * 24 + paddingTop + paddingBottom).toInt()

        val width = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> min(desiredWidth, widthSize)
            else -> desiredWidth
        }
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> min(desiredHeight, heightSize)
            else -> desiredHeight
        }
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paddingLeft = 8 * density
        paddingTop = 4 * density
        paddingRight = 8 * density
        paddingBottom = 4 * density

        updateFontSize()

        val cols = ((w - paddingLeft - paddingRight) / charWidth).toInt().coerceAtLeast(1)
        val rows = ((h - paddingTop - paddingBottom) / charHeight).toInt().coerceAtLeast(1)

        emulator?.resize(cols, rows)
        emulator?.buffer?.resize(cols, rows)
        onSizeChanged?.invoke(cols, rows)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val em = emulator ?: return
        val buf = em.buffer

        // Draw background
        bgPaint.color = backgroundColor
        canvas.drawColor(backgroundColor)

        val cols = buf.columns
        val rows = buf.rows

        // Calculate visible area
        val startX = paddingLeft
        val startY = paddingTop - ascent

        // Draw each cell
        var y = startY
        for (row in 0 until rows) {
            var x = startX
            for (col in 0 until cols) {
                val cell = buf.getCell(row, col)

                // Draw background
                val bg = if (cell.reverse) cell.foregroundColor else cell.backgroundColor
                if (bg != 0 || cell.reverse) {
                    val bgColor = if (cell.reverse && cell.backgroundColor == 0) {
                        TerminalColors.toArgb(if (cell.reverse) cell.foregroundColor else 7)
                    } else {
                        TerminalColors.toArgb(bg)
                    }
                    if (bgColor != backgroundColor and 0xFFFFFF) {
                        bgPaint.color = (0xFF000000.toInt()) or bgColor
                        canvas.drawRect(
                            x, y + ascent,
                            x + charWidth, y + descent,
                            bgPaint
                        )
                    }
                }

                // Draw character
                if (cell.char != ' ') {
                    val fg = if (cell.reverse) cell.backgroundColor else cell.foregroundColor
                    val fgColor = TerminalColors.toArgb(fg)
                    fgPaint.color = (0xFF000000.toInt()) or fgColor
                    fgPaint.isFakeBoldText = cell.bold
                    fgPaint.isUnderlineText = cell.underline
                    fgPaint.isStrikeThruText = cell.strikethrough
                    fgPaint.textSkewX = if (cell.italic) -0.2f else 0f
                    canvas.drawText(cell.char.toString(), x, y, fgPaint)
                }

                x += charWidth
            }
            y += charHeight
        }

        // Draw cursor
        if (em.cursorVisible && scrollOffset == 0) {
            val cursorX = startX + buf.cursorCol * charWidth
            val cursorY = paddingTop + buf.cursorRow * charHeight

            // Blink effect
            val now = System.currentTimeMillis()
            if (now - lastBlinkTime > blinkInterval) {
                cursorVisible = !cursorVisible
                lastBlinkTime = now
                postInvalidate()
            }

            if (cursorVisible) {
                cursorPaint.color = cursorColor
                cursorPaint.alpha = 180
                // Draw block cursor
                canvas.drawRect(
                    cursorX, cursorY,
                    cursorX + charWidth, cursorY + charHeight,
                    cursorPaint
                )
                // Draw the character under cursor in background color
                val cell = buf.getCell(buf.cursorRow, buf.cursorCol)
                if (cell.char != ' ') {
                    fgPaint.color = backgroundColor
                    fgPaint.isFakeBoldText = cell.bold
                    canvas.drawText(cell.char.toString(), cursorX, cursorY - ascent, fgPaint)
                }
            }
        }

        // Draw selection
        if (selecting && selStartRow >= 0 && selEndRow >= 0) {
            // Implementation for selection rendering
        }
    }

    private fun posToCell(x: Float, y: Float): Pair<Int, Int> {
        val col = ((x - paddingLeft) / charWidth).toInt().coerceIn(0, (emulator?.buffer?.columns ?: 1) - 1)
        val row = ((y - paddingTop) / charHeight).toInt().coerceIn(0, (emulator?.buffer?.rows ?: 1) - 1)
        return Pair(row, col)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    // --- Keyboard input ---

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection(this, true)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val em = emulator ?: return super.onKeyDown(keyCode, event)

        // Handle special keys
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { em.sendKey(SpecialKey.UP); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { em.sendKey(SpecialKey.DOWN); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { em.sendKey(SpecialKey.LEFT); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { em.sendKey(SpecialKey.RIGHT); return true }
            KeyEvent.KEYCODE_ENTER -> { em.sendKey(SpecialKey.ENTER); return true }
            KeyEvent.KEYCODE_DEL -> { em.sendKey(SpecialKey.BACKSPACE); return true }
            KeyEvent.KEYCODE_TAB -> { em.sendKey(SpecialKey.TAB); return true }
            KeyEvent.KEYCODE_ESCAPE -> { em.sendKey(SpecialKey.ESCAPE); return true }
            KeyEvent.KEYCODE_HOME -> { em.sendKey(SpecialKey.HOME); return true }
            KeyEvent.KEYCODE_MOVE_END -> { em.sendKey(SpecialKey.END); return true }
            KeyEvent.KEYCODE_PAGE_UP -> { em.sendKey(SpecialKey.PAGE_UP); return true }
            KeyEvent.KEYCODE_PAGE_DOWN -> { em.sendKey(SpecialKey.PAGE_DOWN); return true }
            KeyEvent.KEYCODE_INSERT -> { em.sendKey(SpecialKey.INSERT); return true }
            KeyEvent.KEYCODE_FORWARD_DEL -> { em.sendKey(SpecialKey.DELETE); return true }
            KeyEvent.KEYCODE_F1 -> { em.sendKey(SpecialKey.F1); return true }
            KeyEvent.KEYCODE_F2 -> { em.sendKey(SpecialKey.F2); return true }
            KeyEvent.KEYCODE_F3 -> { em.sendKey(SpecialKey.F3); return true }
            KeyEvent.KEYCODE_F4 -> { em.sendKey(SpecialKey.F4); return true }
            KeyEvent.KEYCODE_F5 -> { em.sendKey(SpecialKey.F5); return true }
            KeyEvent.KEYCODE_F6 -> { em.sendKey(SpecialKey.F6); return true }
            KeyEvent.KEYCODE_F7 -> { em.sendKey(SpecialKey.F7); return true }
            KeyEvent.KEYCODE_F8 -> { em.sendKey(SpecialKey.F8); return true }
            KeyEvent.KEYCODE_F9 -> { em.sendKey(SpecialKey.F9); return true }
            KeyEvent.KEYCODE_F10 -> { em.sendKey(SpecialKey.F10); return true }
            KeyEvent.KEYCODE_F11 -> { em.sendKey(SpecialKey.F11); return true }
            KeyEvent.KEYCODE_F12 -> { em.sendKey(SpecialKey.F12); return true }
        }

        // Handle Ctrl+key
        if (event.isCtrlPressed) {
            val ch = (event.unicodeCharacter and 0x7F).toChar()
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                em.sendCtrlKey(ch)
                return true
            }
        }

        // Handle regular character input
        val ch = event.unicodeCharacter
        if (ch != 0) {
            em.write(ch.toString())
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    fun setFontScale(scale: Float) {
        fontScale = scale
        updateFontSize()
        requestLayout()
        postInvalidate()
    }
}

/**
 * Input connection that handles composing text and sends characters to the terminal.
 */
class TerminalInputConnection(
    private val view: TerminalView,
    private val fullEditor: Boolean
) : BaseInputConnection(view, fullEditor) {

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        view.emulator?.write(text.toString())
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        // Handle as backspace for before text
        repeat(beforeLength) {
            view.emulator?.sendKey(SpecialKey.BACKSPACE)
        }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            view.onKeyDown(event.keyCode, event)
        }
        return true
    }

    override fun finishComposingText(): Boolean {
        return true
    }

    override fun getEditable(): android.text.Editable {
        return android.text.Editable.Factory.getInstance().newEditable("")
    }
}
