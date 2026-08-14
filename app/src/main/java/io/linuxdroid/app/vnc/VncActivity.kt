package io.linuxdroid.app.vnc

import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.linuxdroid.app.data.LocalRepository
import kotlinx.coroutines.launch

/** Internal VNC viewer with a movable control palette suited to touch devices. */
class VncActivity : AppCompatActivity() {
    private lateinit var canvas: VncCanvasView
    private lateinit var status: TextView
    private lateinit var root: FrameLayout
    private lateinit var controlPalette: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(20, 12, 20, 12)
            text = "Preparing VNC…"
            setBackgroundColor(0xA6000000.toInt())
        }
        canvas = VncCanvasView(this).apply {
            onStatus = { value -> status.text = value }
            onRemoteClipboard = { text ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("LinuxDroid VNC", text))
            }
        }
        root.addView(canvas, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)

        lifecycleScope.launch {
            val profile = LocalRepository(this@VncActivity).settings().vnc
            if (profile.keepScreenAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (profile.showOnScreenControls) addFloatingControls()
            canvas.connect(profile)
        }
    }

    override fun onDestroy() {
        canvas.disconnect()
        super.onDestroy()
    }

    private fun addFloatingControls() {
        controlPalette = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            background = GradientDrawable().apply {
                setColor(0xE61C2330.toInt())
                cornerRadius = 22f
                setStroke(1, 0x55FFFFFF)
            }
        }
        val handle = TextView(this).apply {
            text = "VNC controls  ·  drag"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(8, 2, 8, 8)
        }
        controlPalette.addView(handle)
        controlPalette.addView(controlRow("Mouse", listOf(
            "Left" to { canvas.sendSpecialPointer(1) },
            "Right" to { canvas.sendSpecialPointer(4) },
            "Middle" to { canvas.sendSpecialPointer(2) },
            "Scale" to { canvas.toggleScalingMode() }
        )))
        controlPalette.addView(controlRow("Mods", listOf(
            "CTRL" to {},
            "ALT" to {},
            "SHIFT" to {}
        )))
        controlPalette.addView(controlRow("Keys", listOf(
            "ESC" to { canvas.sendSpecial(0xff1b) },
            "Tab" to { canvas.sendSpecial(0xff09) }
        )))
        controlPalette.addView(controlRow("Nav", listOf(
            "←" to { canvas.sendSpecial(0xff51) },
            "↑" to { canvas.sendSpecial(0xff52) },
            "↓" to { canvas.sendSpecial(0xff54) },
            "→" to { canvas.sendSpecial(0xff53) }
        )))
        controlPalette.addView(controlRow("Tools", listOf(
            "Keyboard" to {
                canvas.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(canvas, InputMethodManager.SHOW_IMPLICIT)
            },
            "Hide" to { controlPalette.visibility = View.GONE },
            "Close" to { finish() }
        )))

        val paletteParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, 20, 28)
        }
        root.addView(controlPalette, paletteParams)
        makePaletteDraggable(handle)

        val show = Button(this).apply {
            text = "Controls"
            isAllCaps = false
            setOnClickListener { controlPalette.visibility = View.VISIBLE }
        }
        root.addView(show, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(20, 0, 0, 28)
        })
    }

    private fun controlRow(label: String, actions: List<Pair<String, () -> Unit>>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@VncActivity).apply {
            text = label
            textSize = 10f
            setTextColor(0xFFB6C4D8.toInt())
            setPadding(6, 0, 6, 0)
        })
        actions.forEach { (title, action) ->
            addView(Button(this@VncActivity).apply {
                text = title
                textSize = 11f
                isAllCaps = false
                minHeight = 0
                minimumHeight = 0
                setPadding(10, 0, 10, 0)
                val modifier = when (title) {
                    "CTRL" -> 0xffe3
                    "ALT" -> 0xffe9
                    "SHIFT" -> 0xffe1
                    else -> null
                }
                if (modifier != null) alpha = .68f
                setOnClickListener {
                    if (modifier == null) action()
                    else {
                        isSelected = !isSelected
                        alpha = if (isSelected) 1f else .68f
                        canvas.setModifier(modifier, isSelected)
                    }
                }
            })
        }
    }

    private fun makePaletteDraggable(handle: View) {
        var originX = 0f
        var originY = 0f
        var startLeft = 0
        var startTop = 0
        handle.setOnTouchListener { _, event ->
            val params = controlPalette.layoutParams as FrameLayout.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    originX = event.rawX
                    originY = event.rawY
                    startLeft = controlPalette.left
                    startTop = controlPalette.top
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.gravity = Gravity.TOP or Gravity.START
                    params.leftMargin = (startLeft + event.rawX - originX).toInt().coerceAtLeast(0)
                    params.topMargin = (startTop + event.rawY - originY).toInt().coerceAtLeast(0)
                    controlPalette.layoutParams = params
                    true
                }
                else -> true
            }
        }
    }
}
