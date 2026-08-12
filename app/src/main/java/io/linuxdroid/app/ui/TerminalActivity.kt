package io.linuxdroid.app.ui

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.linuxdroid.app.engine.LinuxRuntime

class TerminalActivity : AppCompatActivity(), TerminalViewClient {
    private lateinit var terminalView: TerminalView
    private var control = false
    private var alt = false
    private var shift = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = LinuxRuntime.controller(this).session
        if (session == null) {
            setContentView(TextView(this).apply { text = "No Linux session is running."; setPadding(32, 32, 32, 32) })
            return
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        terminalView = TerminalView(this, null).apply {
            setTerminalViewClient(this@TerminalActivity)
            setTextSize(14)
            attachSession(session)
        }
        root.addView(terminalView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildExtraKeys(session), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        LinuxRuntime.controller(this).onTerminalChanged = { changed ->
            if (changed === session) runOnUiThread { terminalView.onScreenUpdated() }
        }
    }

    override fun onDestroy() {
        if (LinuxRuntime.controller(this).onTerminalChanged != null) LinuxRuntime.controller(this).onTerminalChanged = null
        super.onDestroy()
    }

    private fun buildExtraKeys(session: TerminalSession): HorizontalScrollView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8, 8, 8, 8) }
        fun modifier(label: String, set: (Boolean) -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { isSelected = !isSelected; alpha = if (isSelected) 1f else .65f; set(isSelected) }
            alpha = .65f
            row.addView(this)
        }
        modifier("CTRL") { control = it }
        modifier("ALT") { alt = it }
        modifier("SHIFT") { shift = it }
        fun escape(label: String, data: String) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { session.write(data) }
            row.addView(this)
        }
        escape("TAB", "\t")
        escape("ESC", "\u001b")
        escape("←", "\u001b[D")
        escape("↑", "\u001b[A")
        escape("↓", "\u001b[B")
        escape("→", "\u001b[C")
        Button(this).apply {
            text = "Keyboard"
            isAllCaps = false
            setOnClickListener {
                terminalView.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            }
            row.addView(this)
        }
        return HorizontalScrollView(this).apply { addView(row) }
    }

    override fun onScale(scale: Float): Float = scale.coerceIn(.7f, 2f)
    override fun onSingleTapUp(e: MotionEvent) {
        terminalView.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = terminalView.hasFocus()
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = control
    override fun readAltKey(): Boolean = alt
    override fun readShiftKey(): Boolean = shift
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        session.writeCodePoint(ctrlDown || control, codePoint)
        return true
    }
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Terminal error", e) }
}
