package io.linuxdroid.app.vnc

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.linuxdroid.app.data.LocalRepository
import io.linuxdroid.app.data.VncProfile
import kotlinx.coroutines.launch

/** Internal mobile VNC viewer with touchpad/direct-touch input and an on-screen control strip. */
class VncActivity : AppCompatActivity() {
    private lateinit var canvas: VncCanvasView
    private lateinit var status: TextView
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
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
        root.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(canvas, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        lifecycleScope.launch {
            val profile = LocalRepository(this@VncActivity).settings().vnc
            if (profile.keepScreenAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (profile.showOnScreenControls) {
                root.addView(buildControls(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            canvas.connect(profile)
        }
    }

    override fun onDestroy() {
        canvas.disconnect()
        super.onDestroy()
    }

    private fun buildControls(): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = 0
            setOnClickListener { action() }
            row.addView(this)
        }
        fun modifier(label: String, keySym: Int) = Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = 0
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
        button("Disconnect") { finish() }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }
}
