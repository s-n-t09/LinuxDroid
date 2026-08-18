package io.linuxdroid.app.vnc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.linuxdroid.app.data.LocalRepository
import io.linuxdroid.app.data.VncProfile
import kotlinx.coroutines.launch

/** Internal mobile VNC viewer with a complete bottom key strip and optional floating game controls. */
class VncActivity : AppCompatActivity() {
    private lateinit var canvas: VncCanvasView
    private lateinit var status: TextView
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private var gamepadOverlay: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(20, 12, 20, 12)
            text = "Preparing VNC…"
        }
        canvas = VncCanvasView(this).apply {
            onStatus = { value -> status.text = value }
            onRemoteClipboard = { text ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("LinuxDroid VNC", text))
            }
        }
        content.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(canvas, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        lifecycleScope.launch {
            val profile = LocalRepository(this@VncActivity).settings().vnc
            requestedOrientation = if (profile.forceLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            if (profile.keepScreenAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (profile.showOnScreenControls) {
                content.addView(buildBottomControls(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            addGamepad(profile)
            canvas.connect(profile)
        }
    }

    override fun onDestroy() {
        canvas.disconnect()
        super.onDestroy()
    }

    private fun buildBottomControls(): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0xED111827.toInt())
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            textSize = 12f
            setOnClickListener { action() }
            row.addView(this)
        }
        fun modifier(label: String, keySym: Int) = Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            textSize = 12f
            alpha = .65f
            setOnClickListener {
                isSelected = !isSelected
                alpha = if (isSelected) 1f else .65f
                canvas.setModifier(keySym, isSelected)
            }
            row.addView(this)
        }
        fun key(label: String, keySym: Int) = button(label) { canvas.sendSpecial(keySym) }

        modifier("CTRL", 0xffe3)
        modifier("ALT", 0xffe9)
        modifier("SHIFT", 0xffe1)
        key("ESC", 0xff1b)
        key("TAB", 0xff09)
        key("←", 0xff51)
        key("↑", 0xff52)
        key("↓", 0xff54)
        key("→", 0xff53)
        key("HOME", 0xff50)
        key("END", 0xff57)
        key("F1", 0xffbe)
        key("F2", 0xffbf)
        key("F3", 0xffc0)
        key("F4", 0xffc1)
        key("F5", 0xffc2)
        key("F6", 0xffc3)
        key("F7", 0xffc4)
        key("F8", 0xffc5)
        key("F9", 0xffc6)
        key("F10", 0xffc7)
        key("F11", 0xffc8)
        key("F12", 0xffc9)
        button("Left click") { canvas.sendSpecialPointer(1) }
        button("Right click") { canvas.sendSpecialPointer(4) }
        button("Middle click") { canvas.sendSpecialPointer(2) }
        button("Scale") { canvas.toggleScalingMode() }
        button("Keyboard") {
            canvas.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(canvas, InputMethodManager.SHOW_IMPLICIT)
        }
        button("Gamepad") { toggleGamepad() }
        button("Disconnect") { finish() }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /** Adds independent, card-free buttons so the gamepad stays visible without obscuring the desktop. */
    private fun addGamepad(profile: VncProfile) {
        val overlay = FrameLayout(this).apply {
            visibility = if (profile.floatingGamepadEnabled) View.VISIBLE else View.GONE
            isClickable = false
            isFocusable = false
        }
        val opacity = profile.floatingGamepadOpacity.coerceIn(25, 90) / 100f
        val bottom = 106
        fun add(label: String, keySym: Int, gravity: Int, left: Int = 0, right: Int = 0, below: Int) {
            overlay.addView(
                gameButton(label, keySym, opacity),
                FrameLayout.LayoutParams(dp(54), dp(54), gravity).apply {
                    setMargins(dp(left), 0, dp(right), dp(below))
                }
            )
        }

        // Direction buttons occupy the left edge and action buttons the right edge.
        add("↑", 0xff52, Gravity.BOTTOM or Gravity.START, left = 78, below = bottom + 72)
        add("←", 0xff51, Gravity.BOTTOM or Gravity.START, left = 18, below = bottom + 18)
        add("→", 0xff53, Gravity.BOTTOM or Gravity.START, left = 138, below = bottom + 18)
        add("↓", 0xff54, Gravity.BOTTOM or Gravity.START, left = 78, below = bottom - 36)

        add("Y", 's'.code, Gravity.BOTTOM or Gravity.END, right = 78, below = bottom + 72)
        add("X", 'a'.code, Gravity.BOTTOM or Gravity.END, right = 138, below = bottom + 18)
        add("B", 'x'.code, Gravity.BOTTOM or Gravity.END, right = 18, below = bottom + 18)
        add("A", 'z'.code, Gravity.BOTTOM or Gravity.END, right = 78, below = bottom - 36)

        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        gamepadOverlay = overlay
    }

    private fun toggleGamepad() {
        val overlay = gamepadOverlay ?: return
        overlay.visibility = if (overlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        status.text = if (overlay.visibility == View.VISIBLE) "Virtual gamepad enabled." else "Virtual gamepad hidden."
    }

    private fun gameButton(label: String, keySym: Int, opacity: Float): Button = Button(this).apply {
        text = label
        textSize = 17f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        alpha = opacity
        elevation = dp(5).toFloat()
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xD9344B73.toInt())
            setStroke(dp(1), 0xB0E8F2FF.toInt())
        }
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> canvas.setVirtualKey(keySym, true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> canvas.setVirtualKey(keySym, false)
            }
            true
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
