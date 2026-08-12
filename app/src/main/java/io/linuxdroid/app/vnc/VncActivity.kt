package io.linuxdroid.app.vnc

import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.linuxdroid.app.data.LocalRepository
import kotlinx.coroutines.launch

class VncActivity : AppCompatActivity() {
    private lateinit var canvas: VncCanvasView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        status = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setPadding(20, 12, 20, 12)
            text = "Preparing VNC…"
        }
        canvas = VncCanvasView(this).apply {
            onStatus = { value -> status.text = value }
        }
        root.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(canvas, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildControls(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        lifecycleScope.launch {
            val profile = LocalRepository(this@VncActivity).settings().vnc
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
        fun key(label: String, keySym: Int) = Button(this).apply {
            text = label
            setOnClickListener { canvas.sendSpecial(keySym) }
            row.addView(this)
        }
        key("CTRL", 0xffe3)
        key("ALT", 0xffe9)
        key("TAB", 0xff09)
        key("←", 0xff51)
        key("↑", 0xff52)
        key("↓", 0xff54)
        key("→", 0xff53)
        Button(this).apply {
            text = "Keyboard"
            setOnClickListener {
                canvas.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(canvas, InputMethodManager.SHOW_IMPLICIT)
            }
            row.addView(this)
        }
        Button(this).apply {
            text = "Disconnect"
            setOnClickListener { finish() }
            row.addView(this)
        }
        return HorizontalScrollView(this).apply { addView(row) }
    }
}
