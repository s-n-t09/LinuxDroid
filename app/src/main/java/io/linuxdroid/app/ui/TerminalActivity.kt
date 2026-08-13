package io.linuxdroid.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.linuxdroid.app.engine.LinuxRuntime
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class TerminalActivity : AppCompatActivity(), TerminalViewClient {
    private lateinit var terminalView: TerminalView
    private lateinit var tabs: LinearLayout
    private lateinit var controller: io.linuxdroid.app.engine.LinuxSessionController

    private var activeSession: TerminalSession? = null
    private var control = false
    private var alt = false
    private var shift = false
    private var fn = false
    private var textSize = 14

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        controller = LinuxRuntime.controller(this)
        val firstSession = controller.session
        if (firstSession == null) {
            showNoSessionState()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(io.linuxdroid.app.R.color.ld_background))
        }
        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 4)
        }
        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabs)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        terminalView = TerminalView(this, null).apply {
            setTerminalViewClient(this@TerminalActivity)
            setTextSize(textSize)
            isFocusableInTouchMode = true
        }
        root.addView(terminalView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            buildExtraKeys(),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        setContentView(root)

        controller.onTerminalChanged = { changed ->
            runOnUiThread {
                if (changed === activeSession) terminalView.onScreenUpdated()
                refreshTabs()
                if (!changed.isRunning() && changed === activeSession) {
                    controller.sessions.firstOrNull()?.let(::selectSession) ?: finish()
                }
            }
        }
        selectSession(firstSession)
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.onTerminalChanged = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (!::terminalView.isInitialized || controller.sessions.isEmpty()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Leave terminal?")
            .setMessage("Choose whether LinuxDroid should keep the current Linux sessions running in the background.")
            .setPositiveButton("Stop all sessions") { _, _ ->
                lifecycleScope.launch {
                    controller.stop()
                    finish()
                }
            }
            .setNeutralButton("Keep sessions running") { _, _ -> finish() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNoSessionState() {
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(this@TerminalActivity).apply {
                text = "No Linux terminal session is running. Return to the dashboard and start a distribution."
            })
            addView(Button(this@TerminalActivity).apply {
                text = "Back to dashboard"
                setOnClickListener { finish() }
            })
        })
    }

    private fun refreshTabs() {
        if (!::tabs.isInitialized) return
        val available = controller.sessions
        tabs.removeAllViews()
        available.forEach { session ->
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4, 0, 4, 0)
            }
            tab.addView(Button(this).apply {
                text = session.mSessionName
                isAllCaps = false
                alpha = if (session === activeSession) 1f else .7f
                setOnClickListener { selectSession(session) }
            })
            tab.addView(Button(this).apply {
                text = "×"
                contentDescription = "Close ${session.mSessionName}"
                isAllCaps = false
                setOnClickListener {
                    lifecycleScope.launch { controller.closeSession(session) }
                }
            })
            tabs.addView(tab)
        }
        tabs.addView(Button(this).apply {
            text = "+"
            contentDescription = "New terminal session"
            textSize = 20f
            setOnClickListener {
                lifecycleScope.launch {
                    runCatching { controller.openAdditionalSession() }
                        .onSuccess { selectSession(it) }
                        .onFailure { Toast.makeText(this@TerminalActivity, it.message ?: "Unable to open a new shell.", Toast.LENGTH_LONG).show() }
                }
            }
        })
    }

    private fun selectSession(session: TerminalSession) {
        if (!::terminalView.isInitialized || !session.isRunning()) return
        activeSession = session
        terminalView.attachSession(session)
        terminalView.requestFocus()
        terminalView.onScreenUpdated()
        refreshTabs()
        showKeyboard()
    }

    private fun buildExtraKeys(): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
        }
        fun keyButton(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = 0
            setOnClickListener { action() }
            row.addView(this)
        }
        fun modifier(label: String, set: (Boolean) -> Unit) {
            keyButton(label) {
                val button = row.findViewWithTag<Button>(label)
                button?.isSelected = !(button?.isSelected ?: false)
                button?.alpha = if (button?.isSelected == true) 1f else .65f
                set(button?.isSelected == true)
            }.apply {
                tag = label
                alpha = .65f
            }
        }
        fun escape(label: String, data: String) = keyButton(label) { activeSession?.write(data) }

        modifier("CTRL") { control = it }
        modifier("ALT") { alt = it }
        modifier("SHIFT") { shift = it }
        modifier("FN") { fn = it }
        escape("ESC", "\u001b")
        escape("TAB", "\t")
        escape("DEL", "\u007f")
        escape("ENTER", "\r")
        escape("HOME", "\u001b[H")
        escape("END", "\u001b[F")
        escape("PG↑", "\u001b[5~")
        escape("PG↓", "\u001b[6~")
        escape("←", "\u001b[D")
        escape("↑", "\u001b[A")
        escape("↓", "\u001b[B")
        escape("→", "\u001b[C")
        escape("F1", "\u001bOP")
        escape("F2", "\u001bOQ")
        escape("F3", "\u001bOR")
        escape("F4", "\u001bOS")
        escape("F5", "\u001b[15~")
        escape("F6", "\u001b[17~")
        escape("F7", "\u001b[18~")
        escape("F8", "\u001b[19~")
        escape("F9", "\u001b[20~")
        escape("F10", "\u001b[21~")
        escape("F11", "\u001b[23~")
        escape("F12", "\u001b[24~")
        keyButton("COPY") { copyTranscript() }
        keyButton("PASTE") { activeSession?.onPasteTextFromClipboard() }
        keyButton("Keyboard") { showKeyboard() }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun copyTranscript() {
        val session = activeSession ?: return
        val text = session.emulator.screen.getTranscriptText()
        if (text.isBlank()) {
            Toast.makeText(this, "There is no terminal text to copy.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("LinuxDroid terminal", text))
        Toast.makeText(this, "Terminal transcript copied.", Toast.LENGTH_SHORT).show()
    }

    private fun showKeyboard() {
        if (!::terminalView.isInitialized) return
        terminalView.requestFocus()
        terminalView.post {
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(terminalView, InputMethodManager.SHOW_FORCED)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && activeSession != null) showKeyboard()
    }

    override fun onScale(scale: Float): Float {
        if (::terminalView.isInitialized) {
            val updated = (textSize * scale).roundToInt().coerceIn(8, 32)
            if (updated != textSize) {
                textSize = updated
                terminalView.setTextSize(textSize)
                terminalView.onScreenUpdated()
            }
        }
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent) = showKeyboard()
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = ::terminalView.isInitialized && terminalView.hasFocus()
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean {
        terminalView.startTextSelectionMode(event)
        return true
    }
    override fun readControlKey(): Boolean = control
    override fun readAltKey(): Boolean = alt
    override fun readShiftKey(): Boolean = shift
    override fun readFnKey(): Boolean = fn
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
