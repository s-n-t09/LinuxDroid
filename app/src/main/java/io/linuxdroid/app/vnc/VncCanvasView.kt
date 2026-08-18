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
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.linuxdroid.app.data.VncInputMode
import io.linuxdroid.app.data.VncProfile
import io.linuxdroid.app.data.VncScalingMode
import kotlin.math.abs

/**
 * Internal VNC canvas with the mobile interaction model expected from a desktop viewer:
 * direct touch or touchpad input, pinch zoom, pan, button gestures, scrolling and hardware keys.
 */
class VncCanvasView(context: Context) : View(context), RfbClient.Listener {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val heldModifiers = mutableSetOf<Int>()
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = scalingMode == VncScalingMode.ONE_TO_ONE

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (scalingMode != VncScalingMode.ONE_TO_ONE) return false
            userScale = (userScale * detector.scaleFactor).coerceIn(0.25f, 5f)
            invalidate()
            return true
        }
    })

    private var client: RfbClient? = null
    private var bitmap: Bitmap? = null
    private var desktopWidth = 1
    private var desktopHeight = 1
    private var connected = false
    private var viewOnly = false
    private var inputMode = VncInputMode.TOUCHPAD
    private var scalingMode = VncScalingMode.FIT
    private var userScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var pointerX = 0
    private var pointerY = 0
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = false
    private var twoFingerOriginY = 0f

    var onStatus: (String) -> Unit = {}
    var onRemoteClipboard: (String) -> Unit = {}

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    fun connect(profile: VncProfile) {
        disconnect()
        viewOnly = profile.viewOnly
        inputMode = profile.inputMode
        scalingMode = profile.scalingMode
        userScale = 1f
        panX = 0f
        panY = 0f
        onStatus("Connecting to ${profile.host}:${profile.port}…")
        client = RfbClient(profile, this).also { it.connect() }
    }

    fun disconnect() {
        heldModifiers.forEach { client?.sendKey(it, false) }
        heldModifiers.clear()
        client?.disconnect()
        client = null
        connected = false
    }

    fun sendSpecial(keySym: Int) {
        if (viewOnly) return
        client?.sendKey(keySym, true)
        client?.sendKey(keySym, false)
    }

    fun sendSpecialPointer(buttonMask: Int) {
        if (viewOnly) return
        click(buttonMask)
    }

    /** Sends a press or release for a virtual gamepad key while it is held on screen. */
    fun setVirtualKey(keySym: Int, held: Boolean) {
        if (viewOnly) return
        client?.sendKey(keySym, held)
    }

    fun setModifier(keySym: Int, held: Boolean) {
        if (viewOnly) return
        if (held) heldModifiers += keySym else heldModifiers -= keySym
        client?.sendKey(keySym, held)
    }

    fun toggleScalingMode() {
        scalingMode = if (scalingMode == VncScalingMode.FIT) VncScalingMode.ONE_TO_ONE else VncScalingMode.FIT
        if (scalingMode == VncScalingMode.FIT) {
            userScale = 1f
            panX = 0f
            panY = 0f
        }
        onStatus(if (scalingMode == VncScalingMode.FIT) "VNC: fit to screen" else "VNC: one-to-one / pinch zoom")
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        val target = targetRect()
        synchronized(image) { canvas.drawBitmap(image, null, target, paint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        if (!connected || viewOnly) return true
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) return true

        if (event.pointerCount >= 2) {
            handleMultiTouch(event)
            return true
        }
        when (inputMode) {
            VncInputMode.DIRECT_TOUCH -> handleDirectTouch(event)
            VncInputMode.TOUCHPAD -> handleTouchpad(event)
        }
        return true
    }

    private fun handleDirectTouch(event: MotionEvent) {
        val coordinates = mapToDesktop(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
                moved = false
                client?.sendPointer(1, coordinates.first, coordinates.second)
            }
            MotionEvent.ACTION_MOVE -> {
                moved = moved || abs(event.x - lastX) > 4f || abs(event.y - lastY) > 4f
                client?.sendPointer(1, coordinates.first, coordinates.second)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> client?.sendPointer(0, coordinates.first, coordinates.second)
        }
        lastX = event.x
        lastY = event.y
    }

    private fun handleTouchpad(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downTime = event.eventTime
                moved = false
                // Touchpad is deliberately relative: touching elsewhere on the phone
                // must not teleport the remote pointer to that absolute position.
            }
            MotionEvent.ACTION_MOVE -> {
                val target = targetRect()
                val xScale = if (target.width() > 0f) desktopWidth / target.width() else 1f
                val yScale = if (target.height() > 0f) desktopHeight / target.height() else 1f
                val dx = (event.x - lastX) * xScale
                val dy = (event.y - lastY) * yScale
                moved = moved || abs(dx) > 2f || abs(dy) > 2f
                pointerX = (pointerX + dx).toInt().coerceIn(0, desktopWidth - 1)
                pointerY = (pointerY + dy).toInt().coerceIn(0, desktopHeight - 1)
                client?.sendPointer(0, pointerX, pointerY)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!moved && event.eventTime - downTime < 300) click(1)
            }
        }
    }

    private fun handleMultiTouch(event: MotionEvent) {
        val midpointY = (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                twoFingerOriginY = midpointY
                lastX = event.getX(0)
                lastY = event.getY(0)
                downTime = event.eventTime
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = midpointY - twoFingerOriginY
                if (abs(deltaY) > 28f) {
                    scroll(if (deltaY > 0) 16 else 8)
                    twoFingerOriginY = midpointY
                    moved = true
                }
                if (!scaleDetector.isInProgress && scalingMode == VncScalingMode.ONE_TO_ONE) {
                    panX += event.getX(0) - lastX
                    panY += event.getY(0) - lastY
                    lastX = event.getX(0)
                    lastY = event.getY(0)
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (!moved && event.pointerCount == 2 && event.eventTime - downTime < 300) click(4)
            }
        }
    }

    private fun click(buttonMask: Int) {
        client?.sendPointer(buttonMask, pointerX, pointerY)
        client?.sendPointer(0, pointerX, pointerY)
    }

    private fun scroll(buttonMask: Int) {
        client?.sendPointer(buttonMask, pointerX, pointerY)
        client?.sendPointer(0, pointerX, pointerY)
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
                repeat(beforeLength) { sendSpecial(0xff08) }
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

    override fun onConnected(desktopName: String, width: Int, height: Int) {
        post {
            connected = true
            desktopWidth = width
            desktopHeight = height
            pointerX = width / 2
            pointerY = height / 2
            onStatus("Connected: $desktopName ($width×$height)")
            invalidate()
        }
    }

    override fun onFramebuffer(bitmap: Bitmap) {
        synchronized(bitmap) { this.bitmap = bitmap }
        postInvalidateOnAnimation()
    }

    override fun onClipboard(text: String) = onRemoteClipboard(text)
    override fun onBell() {
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onDisconnected(error: Throwable?) {
        post {
            connected = false
            bitmap = null
            onStatus(error?.message?.let { "VNC disconnected: $it" } ?: "VNC disconnected")
            invalidate()
        }
    }

    private fun targetRect(): RectF {
        val fitScale = minOf(width.toFloat() / desktopWidth, height.toFloat() / desktopHeight)
        val scale = if (scalingMode == VncScalingMode.FIT) fitScale else userScale
        val drawWidth = desktopWidth * scale
        val drawHeight = desktopHeight * scale
        val left = (width - drawWidth) / 2f + panX
        val top = (height - drawHeight) / 2f + panY
        return RectF(left, top, left + drawWidth, top + drawHeight)
    }

    private fun mapToDesktop(x: Float, y: Float): Pair<Int, Int> {
        val target = targetRect()
        return (((x - target.left) * desktopWidth / target.width()).toInt().coerceIn(0, desktopWidth - 1)) to
            (((y - target.top) * desktopHeight / target.height()).toInt().coerceIn(0, desktopHeight - 1))
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
        KeyEvent.KEYCODE_F5 -> 0xffc2
        KeyEvent.KEYCODE_F6 -> 0xffc3
        KeyEvent.KEYCODE_F7 -> 0xffc4
        KeyEvent.KEYCODE_F8 -> 0xffc5
        KeyEvent.KEYCODE_F9 -> 0xffc6
        KeyEvent.KEYCODE_F10 -> 0xffc7
        KeyEvent.KEYCODE_F11 -> 0xffc8
        KeyEvent.KEYCODE_F12 -> 0xffc9
        else -> event.unicodeChar.takeIf { it > 0 }
    }
}
