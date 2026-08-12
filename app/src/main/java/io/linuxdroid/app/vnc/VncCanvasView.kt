package io.linuxdroid.app.vnc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.linuxdroid.app.data.VncProfile

class VncCanvasView(context: Context) : View(context), RfbClient.Listener {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var client: RfbClient? = null
    private var bitmap: Bitmap? = null
    private var desktopWidth = 1
    private var desktopHeight = 1
    private var connected = false
    private var viewOnly = false
    var onStatus: (String) -> Unit = {}

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    fun connect(profile: VncProfile) {
        disconnect()
        viewOnly = profile.viewOnly
        onStatus("Connecting to ${profile.host}:${profile.port}…")
        client = RfbClient(profile, this).also { it.connect() }
    }

    fun disconnect() {
        client?.disconnect()
        client = null
        connected = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        synchronized(image) {
            val scale = minOf(width.toFloat() / desktopWidth, height.toFloat() / desktopHeight)
            val drawWidth = desktopWidth * scale
            val drawHeight = desktopHeight * scale
            val left = (width - drawWidth) / 2f
            val top = (height - drawHeight) / 2f
            canvas.drawBitmap(image, null, RectF(left, top, left + drawWidth, top + drawHeight), paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        if (!connected || viewOnly) return true
        val coordinates = mapToDesktop(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> client?.sendPointer(1, coordinates.first, coordinates.second)
            MotionEvent.ACTION_MOVE -> client?.sendPointer(if (event.buttonState and MotionEvent.BUTTON_PRIMARY != 0) 1 else 0, coordinates.first, coordinates.second)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> client?.sendPointer(0, coordinates.first, coordinates.second)
        }
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                text.codePoints().forEach { keySym ->
                    client?.sendKey(keySym, true)
                    client?.sendKey(keySym, false)
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) {
                    client?.sendKey(0xff08, true)
                    client?.sendKey(0xff08, false)
                }
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (viewOnly) return true
        keySym(event)?.let { client?.sendKey(it, true); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (viewOnly) return true
        keySym(event)?.let { client?.sendKey(it, false); return true }
        return super.onKeyUp(keyCode, event)
    }

    fun sendSpecial(keySym: Int) {
        if (viewOnly) return
        client?.sendKey(keySym, true)
        client?.sendKey(keySym, false)
    }

    override fun onConnected(desktopName: String, width: Int, height: Int) {
        post {
            connected = true
            desktopWidth = width
            desktopHeight = height
            onStatus("Connected: $desktopName ($width×$height)")
            invalidate()
        }
    }

    override fun onFramebuffer(bitmap: Bitmap) {
        synchronized(bitmap) { this.bitmap = bitmap }
        postInvalidateOnAnimation()
    }

    override fun onClipboard(text: String) = Unit
    override fun onBell() {
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onDisconnected(error: Throwable?) {
        post {
            connected = false
            onStatus(error?.message ?: "VNC disconnected")
        }
    }

    private fun mapToDesktop(x: Float, y: Float): Pair<Int, Int> {
        val scale = minOf(width.toFloat() / desktopWidth, height.toFloat() / desktopHeight)
        val left = (width - desktopWidth * scale) / 2f
        val top = (height - desktopHeight * scale) / 2f
        return (((x - left) / scale).toInt().coerceIn(0, desktopWidth - 1)) to
            (((y - top) / scale).toInt().coerceIn(0, desktopHeight - 1))
    }

    private fun keySym(event: KeyEvent): Int? = when (event.keyCode) {
        KeyEvent.KEYCODE_DEL -> 0xff08
        KeyEvent.KEYCODE_TAB -> 0xff09
        KeyEvent.KEYCODE_ENTER -> 0xff0d
        KeyEvent.KEYCODE_ESCAPE -> 0xff1b
        KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
        KeyEvent.KEYCODE_DPAD_UP -> 0xff52
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
        KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xffe1
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> 0xffe3
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> 0xffe9
        KeyEvent.KEYCODE_F1 -> 0xffbe
        KeyEvent.KEYCODE_F2 -> 0xffbf
        KeyEvent.KEYCODE_F3 -> 0xffc0
        KeyEvent.KEYCODE_F4 -> 0xffc1
        else -> event.unicodeChar.takeIf { it > 0 }
    }
}
