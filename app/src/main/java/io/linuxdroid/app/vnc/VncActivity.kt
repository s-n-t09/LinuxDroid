package io.linuxdroid.app.vnc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.linuxdroid.app.data.GamepadButtonConfig
import io.linuxdroid.app.data.LocalRepository
import io.linuxdroid.app.data.VncProfile
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/** Internal VNC viewer with immersive full-screen output and a configurable virtual gamepad. */
class VncActivity : AppCompatActivity() {
    private lateinit var canvas: VncCanvasView
    private lateinit var status: TextView
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var repository: LocalRepository
    private var gamepadOverlay: FrameLayout? = null
    private var activeProfile: VncProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = LocalRepository(this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(7), dp(16), dp(7))
            textSize = 12f
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
        content.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(canvas, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        enterFullscreen()

        lifecycleScope.launch {
            val profile = repository.settings().vnc
            activeProfile = profile
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    override fun onDestroy() {
        canvas.disconnect()
        super.onDestroy()
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun buildBottomControls(): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
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
        button("Edit pad") { editGamepad() }
        button("Disconnect") { finish() }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun addGamepad(profile: VncProfile) {
        val overlay = FrameLayout(this).apply {
            visibility = if (profile.floatingGamepadEnabled) View.VISIBLE else View.GONE
            isClickable = false
            isFocusable = false
        }
        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        gamepadOverlay = overlay
        overlay.post { renderGamepad(profile) }
    }

    private fun renderGamepad(profile: VncProfile) {
        val overlay = gamepadOverlay ?: return
        if (overlay.width == 0 || overlay.height == 0) {
            overlay.post { renderGamepad(profile) }
            return
        }
        overlay.removeAllViews()
        val occupied = mutableListOf<Rect>()
        profile.gamepadButtons.filter { it.enabled }.forEach { button ->
            val view = gameButton(button, profile)
            val position = resolveInitialPosition(button, occupied, overlay)
            overlay.addView(view, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.TOP or Gravity.START).apply {
                leftMargin = position.first
                topMargin = position.second
            })
            occupied += Rect(position.first, position.second, position.first + dp(54), position.second + dp(54))
        }
    }

    private fun resolveInitialPosition(button: GamepadButtonConfig, occupied: List<Rect>, overlay: FrameLayout): Pair<Int, Int> {
        val size = dp(54)
        val maxX = (overlay.width - size).coerceAtLeast(0)
        val maxY = (overlay.height - size).coerceAtLeast(0)
        var x = (maxX * button.xPercent.coerceIn(0, 100) / 100f).roundToInt()
        var y = (maxY * button.yPercent.coerceIn(0, 100) / 100f).roundToInt()
        repeat(48) {
            val candidate = Rect(x, y, x + size, y + size)
            if (occupied.none { intersectsWithGap(it, candidate) }) return x to y
            y += dp(16)
            if (y > maxY) {
                y = dp(8).coerceAtMost(maxY)
                x = (x + dp(16)) % (maxX + 1)
            }
        }
        return x to y
    }

    private fun toggleGamepad() {
        val overlay = gamepadOverlay ?: return
        overlay.visibility = if (overlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        status.text = if (overlay.visibility == View.VISIBLE) "Virtual gamepad enabled. Drag a button to reposition it." else "Virtual gamepad hidden."
    }

    private fun gameButton(config: GamepadButtonConfig, profile: VncProfile): Button = Button(this).apply {
        text = config.label
        textSize = 17f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        alpha = profile.floatingGamepadOpacity.coerceIn(25, 90) / 100f
        elevation = dp(5).toFloat()
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xD9344B73.toInt())
            setStroke(dp(1), 0xB0E8F2FF.toInt())
        }
        attachGamepadTouch(this, config)
    }

    private fun attachGamepadTouch(button: View, config: GamepadButtonConfig) {
        val slop = dp(9).toFloat()
        var startRawX = 0f
        var startRawY = 0f
        var startLeft = 0
        var startTop = 0
        var pressed = false
        var dragging = false
        button.setOnTouchListener { view, event ->
            val overlay = gamepadOverlay ?: return@setOnTouchListener false
            val params = view.layoutParams as FrameLayout.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startLeft = params.leftMargin
                    startTop = params.topMargin
                    dragging = false
                    pressed = true
                    canvas.setVirtualKey(effectiveKey(config), true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        if (pressed) {
                            canvas.setVirtualKey(effectiveKey(config), false)
                            pressed = false
                        }
                    }
                    if (dragging) {
                        val maxX = (overlay.width - view.width).coerceAtLeast(0)
                        val maxY = (overlay.height - view.height).coerceAtLeast(0)
                        val targetX = (startLeft + dx).roundToInt().coerceIn(0, maxX)
                        val targetY = (startTop + dy).roundToInt().coerceIn(0, maxY)
                        if (!overlapsAnotherButton(overlay, view, targetX, targetY)) {
                            params.leftMargin = targetX
                            params.topMargin = targetY
                            params.gravity = Gravity.TOP or Gravity.START
                            view.layoutParams = params
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pressed) canvas.setVirtualKey(effectiveKey(config), false)
                    if (dragging) saveButtonPosition(config, params.leftMargin, params.topMargin, overlay)
                    true
                }
                else -> true
            }
        }
    }

    private fun effectiveKey(config: GamepadButtonConfig): Int {
        val profile = activeProfile ?: return config.keySym
        if (!profile.invertGamepadDpad) return config.keySym
        return when (config.id) {
            "up" -> 0xff54
            "down" -> 0xff52
            "left" -> 0xff53
            "right" -> 0xff51
            else -> config.keySym
        }
    }

    private fun overlapsAnotherButton(overlay: FrameLayout, current: View, left: Int, top: Int): Boolean {
        val proposed = Rect(left, top, left + current.width, top + current.height)
        for (index in 0 until overlay.childCount) {
            val child = overlay.getChildAt(index)
            if (child === current || child.visibility != View.VISIBLE) continue
            val occupied = Rect(child.left, child.top, child.right, child.bottom)
            if (intersectsWithGap(proposed, occupied)) return true
        }
        return false
    }

    private fun intersectsWithGap(first: Rect, second: Rect): Boolean {
        val gap = dp(8)
        return first.left < second.right + gap && first.right + gap > second.left &&
            first.top < second.bottom + gap && first.bottom + gap > second.top
    }

    private fun saveButtonPosition(config: GamepadButtonConfig, left: Int, top: Int, overlay: FrameLayout) {
        val profile = activeProfile ?: return
        val maxX = (overlay.width - dp(54)).coerceAtLeast(1)
        val maxY = (overlay.height - dp(54)).coerceAtLeast(1)
        val updated = profile.copy(gamepadButtons = profile.gamepadButtons.map {
            if (it.id == config.id) it.copy(
                xPercent = (left * 100f / maxX).roundToInt().coerceIn(0, 100),
                yPercent = (top * 100f / maxY).roundToInt().coerceIn(0, 100)
            ) else it
        })
        updateProfile(updated)
        status.text = "${config.label} position saved."
    }

    private fun editGamepad() {
        val profile = activeProfile ?: return
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(2), dp(20), dp(2))
        }
        profile.gamepadButtons.forEach { config ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = config.label
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(dp(42), dp(48)))
            row.addView(CheckBox(this).apply {
                text = "Show"
                isChecked = config.enabled
                setOnCheckedChangeListener { _, enabled ->
                    updateProfile(profile.copy(gamepadButtons = profile.gamepadButtons.map { if (it.id == config.id) it.copy(enabled = enabled) else it }))
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Delete"
                isAllCaps = false
                setOnClickListener {
                    updateProfile(profile.copy(gamepadButtons = profile.gamepadButtons.filterNot { it.id == config.id }))
                    editGamepad()
                }
            })
            list.addView(row)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit virtual gamepad")
            .setMessage("Tap a button to use it; drag it to move it. Buttons cannot overlap.")
            .setView(list)
            .setNegativeButton("Close", null)
            .setPositiveButton("Add button", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dialog.dismiss()
            chooseGamepadButton()
        }
    }

    private fun chooseGamepadButton() {
        val options = listOf(
            "↑" to 0xff52, "↓" to 0xff54, "←" to 0xff51, "→" to 0xff53,
            "Space" to 0x20, "Enter" to 0xff0d, "Esc" to 0xff1b, "Tab" to 0xff09,
            "F1" to 0xffbe, "F2" to 0xffbf, "F3" to 0xffc0, "F4" to 0xffc1
        )
        AlertDialog.Builder(this)
            .setTitle("Add gamepad button")
            .setItems(options.map { it.first }.toTypedArray()) { _, index ->
                val profile = activeProfile
                if (profile != null) {
                    val slot = nextFreeSlot(profile)
                    val added = GamepadButtonConfig(
                        id = "custom-${UUID.randomUUID()}",
                        label = options[index].first,
                        keySym = options[index].second,
                        xPercent = slot.first,
                        yPercent = slot.second
                    )
                    updateProfile(profile.copy(gamepadButtons = profile.gamepadButtons + added))
                    editGamepad()
                }
            }.show()
    }

    private fun nextFreeSlot(profile: VncProfile): Pair<Int, Int> {
        val candidates = listOf(45 to 42, 55 to 42, 45 to 52, 55 to 52, 35 to 42, 65 to 42, 35 to 52, 65 to 52)
        return candidates.firstOrNull { candidate ->
            profile.gamepadButtons.none { abs(it.xPercent - candidate.first) < 10 && abs(it.yPercent - candidate.second) < 10 }
        } ?: (50 to 50)
    }

    private fun updateProfile(updated: VncProfile) {
        activeProfile = updated
        renderGamepad(updated)
        lifecycleScope.launch {
            val settings = repository.settings()
            repository.saveSettings(settings.copy(vnc = updated))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
