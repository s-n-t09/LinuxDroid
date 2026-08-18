package io.linuxdroid.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import io.linuxdroid.app.data.BrowserChoice
import io.linuxdroid.app.data.DesktopEnvironment
import io.linuxdroid.app.data.InstalledDistro
import io.linuxdroid.app.data.LocalRepository
import io.linuxdroid.app.data.RootfsDefinition
import io.linuxdroid.app.data.RootfsInstaller
import io.linuxdroid.app.data.RootfsNetwork
import io.linuxdroid.app.data.RootfsReleaseClient
import io.linuxdroid.app.data.SetupSelection
import io.linuxdroid.app.data.StartupService
import io.linuxdroid.app.data.VncInputMode
import io.linuxdroid.app.data.VncProfile
import io.linuxdroid.app.data.VncScalingMode
import io.linuxdroid.app.domain.DesktopSetupBuilder
import io.linuxdroid.app.engine.LinuxRuntime
import io.linuxdroid.app.engine.RuntimeInstaller
import io.linuxdroid.app.engine.SessionLogStore
import io.linuxdroid.app.service.LinuxSessionService
import io.linuxdroid.app.ui.TerminalActivity
import io.linuxdroid.app.vnc.VncActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private enum class ActionTone(val background: Int, val foreground: Int) {
        PRIMARY(R.color.ld_action_primary, R.color.ld_on_action_primary),
        SUCCESS(R.color.ld_action_success, R.color.ld_on_action_success),
        INFO(R.color.ld_action_info, R.color.ld_on_action_info),
        VIOLET(R.color.ld_action_violet, R.color.ld_on_action_violet),
        WARNING(R.color.ld_action_warning, R.color.ld_on_action_warning),
        DANGER(R.color.ld_action_danger, R.color.ld_on_action_danger)
    }

    private val local by lazy { LocalRepository(this) }
    private val network = RootfsNetwork()
    private val releaseClient = RootfsReleaseClient()
    private companion object {
        const val PAGE_HOME = 1
        const val PAGE_DISTRIBUTIONS = 2
        const val PAGE_SETTINGS = 3
    }

    private lateinit var installedContainer: LinearLayout
    private lateinit var status: TextView
    private lateinit var installButton: MaterialButton
    private lateinit var pageContainer: FrameLayout
    private lateinit var navigation: BottomNavigationView
    private var selectedPage = PAGE_HOME
    private var releaseDistributions: List<RootfsDefinition> = emptyList()
    private var statusRefreshJob: Job? = null

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildScreen())
        requestFirstRunPermissions()
        refreshInstalled()
        lifecycleScope.launch { loadReleaseSilently() }
    }

    override fun onResume() {
        super.onResume()
        refreshInstalled()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.ld_background))
            addView(buildStatusCard(), linearParams(top = 12, bottom = 4).apply { setMargins(dp(16), dp(12), dp(16), dp(4)) })
            pageContainer = FrameLayout(this@MainActivity)
            addView(pageContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            navigation = BottomNavigationView(this@MainActivity).apply {
                setBackgroundColor(getColor(R.color.ld_surface))
                itemIconTintList = ColorStateList.valueOf(getColor(R.color.ld_action_violet))
                itemTextColor = ColorStateList.valueOf(getColor(R.color.ld_text))
                menu.add(Menu.NONE, PAGE_HOME, 0, "Home").setIcon(android.R.drawable.ic_menu_view)
                menu.add(Menu.NONE, PAGE_DISTRIBUTIONS, 1, "Distros").setIcon(android.R.drawable.ic_menu_agenda)
                menu.add(Menu.NONE, PAGE_SETTINGS, 2, "Settings").setIcon(android.R.drawable.ic_menu_preferences)
                setOnItemSelectedListener { item ->
                    showPage(item.itemId)
                    true
                }
            }
            addView(navigation, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            showPage(PAGE_HOME)
            navigation.selectedItemId = PAGE_HOME
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(0, safeArea.top, 0, safeArea.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        return root
    }

    private fun showPage(page: Int) {
        selectedPage = page
        pageContainer.removeAllViews()
        pageContainer.addView(
            when (page) {
                PAGE_DISTRIBUTIONS -> buildDistributionsPage()
                PAGE_SETTINGS -> buildSettingsPage()
                else -> buildHomePage()
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
    }

    private fun scrollPage(content: LinearLayout): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun pageContent(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(28))
    }

    private fun buildHomePage(): View = scrollPage(pageContent().apply {
        addView(buildHero(), linearParams(bottom = 18))
        addView(sectionTitle("Linux workspace", "One clean place for your running Linux environment"), linearParams(bottom = 10))
        addView(actionButton("Browse distributions", ActionTone.PRIMARY) { navigation.selectedItemId = PAGE_DISTRIBUTIONS }, linearParams(bottom = 10))
        addView(actionButton("Session controls", ActionTone.WARNING) { showSessionControls() }, linearParams(bottom = 10))
        addView(actionButton("Tools and diagnostics", ActionTone.INFO) { navigation.selectedItemId = PAGE_SETTINGS }, linearParams(bottom = 16))
        addView(infoCard("Audio and storage", "PulseAudio uses a private 127.0.0.1:4713 service. VNC, shared storage, audio diagnostics, and LinuxDroid preferences are grouped in Settings."))
    })

    private fun buildDistributionsPage(): View = scrollPage(pageContent().apply {
        addView(sectionTitle("Distributions", "Install, launch, configure, or remove Linux environments"), linearParams(bottom = 12))
        installButton = actionButton("Install distribution", ActionTone.PRIMARY) { showReleaseDistributions() }.apply { isEnabled = releaseDistributions.isNotEmpty() }
        addView(installButton, linearParams(bottom = 10))
        addView(actionButton("Refresh RootFS catalog", ActionTone.INFO) { lifecycleScope.launch { loadReleaseSilently() } }, linearParams(bottom = 22))
        addView(sectionTitle("Installed distributions", "Only one PRoot distribution can run at once"), linearParams(bottom = 10))
        installedContainer = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(installedContainer)
        refreshInstalled()
    })

    private fun buildSettingsPage(): View = scrollPage(pageContent().apply {
        addView(sectionTitle("LinuxDroid settings", "Personalize the runtime defaults used for new and restarted sessions"), linearParams(bottom = 14))
        lifecycleScope.launch {
            val current = local.settings()
            addView(settingSwitch("Enable PulseAudio", "Start the private Android audio service for PRoot sessions", current.pulseAudioEnabled) { enabled ->
                lifecycleScope.launch { local.saveSettings(local.settings().copy(pulseAudioEnabled = enabled)); showTemporaryRootfsStatus("Audio setting saved. Restart the distribution to apply it.") }
            }, linearParams(bottom = 10))
            addView(settingSwitch("Show LinuxDroid MOTD", "Display /etc/motd automatically in each new interactive shell", current.showMotdOnStart) { enabled ->
                lifecycleScope.launch { local.saveSettings(local.settings().copy(showMotdOnStart = enabled)); showTemporaryRootfsStatus("MOTD setting saved. Open a new terminal session to apply it.") }
            }, linearParams(bottom = 10))
            addView(settingSwitch("Keep VNC screen awake", "Prevent screen sleep while the built-in VNC viewer is connected", current.vnc.keepScreenAwake) { enabled ->
                lifecycleScope.launch { val settings = local.settings(); local.saveSettings(settings.copy(vnc = settings.vnc.copy(keepScreenAwake = enabled))); showTemporaryRootfsStatus("VNC setting saved.") }
            }, linearParams(bottom = 10))
        }
        addView(sectionTitle("Tools and diagnostics", "Connection, storage, session controls, and troubleshooting"), linearParams(top = 8, bottom = 10))
        addView(actionButton("Configure VNC", ActionTone.VIOLET) { configureVnc() }, linearParams(bottom = 10))
        addView(actionButton("Shared storage access", ActionTone.SUCCESS) { configureSharedStorage() }, linearParams(bottom = 10))
        addView(actionButton("Linux session controls", ActionTone.WARNING) { showSessionControls() }, linearParams(bottom = 10))
        addView(actionButton("Session diagnostics", ActionTone.INFO) { showSessionLog() }, linearParams(bottom = 10))
        addView(actionButton("Open user guide", ActionTone.INFO) { showUserGuide() }, linearParams(bottom = 16))
        addView(infoCard("Audio diagnostics", "Start a distribution, then run linuxdroid-audio test. Session diagnostics shows the final PulseAudio server log when the private TCP service cannot start."), linearParams(bottom = 10))
        addView(infoCard("Privacy", "LinuxDroid uses a local PRoot runtime. Shared storage remains unavailable until you explicitly grant Android All files access."))
    })

    private fun infoCard(title: String, message: String): View = MaterialCardView(this).apply {
        radius = dp(20).toFloat(); cardElevation = 0f
        setCardBackgroundColor(getColor(R.color.ld_surface_alt)); strokeColor = getColor(R.color.ld_outline); strokeWidth = dp(1)
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15))
            addView(TextView(this@MainActivity).apply { text = title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(getColor(R.color.ld_text)) })
            addView(TextView(this@MainActivity).apply { text = message; textSize = 13f; setPadding(0, dp(5), 0, 0); setTextColor(getColor(R.color.ld_muted)) })
        })
    }

    private fun settingSwitch(title: String, detail: String, checked: Boolean, onChanged: (Boolean) -> Unit): View = MaterialCardView(this).apply {
        radius = dp(18).toFloat(); cardElevation = 0f; setCardBackgroundColor(getColor(R.color.ld_surface)); strokeColor = getColor(R.color.ld_outline); strokeWidth = dp(1)
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(10), dp(12))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply { text = title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(getColor(R.color.ld_text)) })
                addView(TextView(this@MainActivity).apply { text = detail; textSize = 12f; setPadding(0, dp(3), 0, 0); setTextColor(getColor(R.color.ld_muted)) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(MaterialSwitch(this@MainActivity).apply { isChecked = checked; setOnCheckedChangeListener { _, enabled -> onChanged(enabled) } })
        })
    }

    private fun showUserGuide() {
        AlertDialog.Builder(this)
            .setTitle("LinuxDroid guide")
            .setMessage("1. Install a distribution from Distros.\n\n2. Start it, then open Terminal.\n\n3. For sound, run linuxdroid-audio test. If it reports a service error, stop and start the distribution and open Session diagnostics.\n\n4. For files, enable Shared storage and grant All files access; restart the distribution and use /storage/emulated/0.\n\n5. For desktop use, run Desktop setup from a distribution card, then start its VNC script and open the VNC viewer.\n\nIf Android terminates a long session (Signal 9), keep the foreground notification visible and exclude LinuxDroid from battery optimization.")
            .setPositiveButton("Close", null)
            .show()
    }

    private fun buildHero(): View {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(18), dp(20))
            background = roundedGradient(R.color.ld_hero_start, R.color.ld_hero_middle, R.color.ld_hero_end, 28)
            elevation = dp(5).toFloat()
        }
        hero.addView(ImageView(this).apply {
            setImageResource(R.drawable.linuxdroid_logo)
            contentDescription = "LinuxDroid logo"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(66), dp(66)))
        hero.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = "LinuxDroid"
                textSize = 30f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.ld_on_hero))
            })
            addView(TextView(this@MainActivity).apply {
                text = "A colourful home for Linux on Android"
                textSize = 14f
                setPadding(0, dp(3), 0, 0)
                setTextColor(getColor(R.color.ld_on_hero_muted))
            })
            addView(TextView(this@MainActivity).apply {
                text = "PRoot  •  Terminal  •  VNC  •  Audio"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(10), 0, 0)
                setTextColor(getColor(R.color.ld_hero_chip))
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return hero
    }

    private fun buildStatusCard(): View = MaterialCardView(this).apply {
        radius = dp(22).toFloat()
        cardElevation = dp(1).toFloat()
        setCardBackgroundColor(getColor(R.color.ld_surface))
        strokeColor = getColor(R.color.ld_outline)
        strokeWidth = dp(1)
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(View(this@MainActivity).apply {
                background = roundedColor(getColor(R.color.ld_status_ready), 8)
            }, LinearLayout.LayoutParams(dp(10), dp(10)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = "Runtime and RootFS status"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(getColor(R.color.ld_muted))
                })
                status = TextView(this@MainActivity).apply {
                    text = "Checking RootFS 1 and private PulseAudio service…"
                    textSize = 15f
                    setPadding(0, dp(2), 0, 0)
                    setTextColor(getColor(R.color.ld_text))
                }
                addView(status)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.ld_text))
        })
        addView(TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 13f
            setPadding(0, dp(3), 0, 0)
            setTextColor(getColor(R.color.ld_muted))
        })
    }

    private fun actionButton(label: String, tone: ActionTone, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(52)
        minimumHeight = dp(52)
        minWidth = 0
        minimumWidth = 0
        cornerRadius = dp(16)
        insetTop = 0
        insetBottom = 0
        setPadding(dp(16), 0, dp(16), 0)
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        backgroundTintList = ColorStateList.valueOf(getColor(tone.background))
        setTextColor(getColor(tone.foreground))
        setOnClickListener { action() }
    }

    private fun requestFirstRunPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val prefs = getSharedPreferences("onboarding", MODE_PRIVATE)
        if (!prefs.getBoolean("asked_all_files", false) && Build.VERSION.SDK_INT >= 30) {
            prefs.edit().putBoolean("asked_all_files", true).apply()
            AlertDialog.Builder(this)
                .setTitle("Optional shared-storage access")
                .setMessage("LinuxDroid can bind shared storage at /storage/emulated/0 inside Linux only if you grant All files access. A compatible /sdcard path is also provided. This is optional; without it the distribution remains isolated.")
                .setNegativeButton("Keep isolated", null)
                .setPositiveButton("Allow and open settings") { _, _ -> configureSharedStorage() }
                .show()
        }
    }

    private fun configureSharedStorage() {
        lifecycleScope.launch {
            val current = local.settings()
            if (!current.enableAllFilesBinding) local.saveSettings(current.copy(enableAllFilesBinding = true))
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                showTemporaryRootfsStatus("Enable All files access, then start the distribution to mount /storage/emulated/0.")
                requestAllFilesAccess()
            } else {
                showTemporaryRootfsStatus("Shared storage is enabled. Start or restart the distribution to mount /storage/emulated/0.")
            }
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun configureStartupServices(distro: InstalledDistro) {
        lifecycleScope.launch {
            var services = distro.startupServices
            val list = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(4), dp(20), dp(4))
            }
            val scroll = ScrollView(this@MainActivity).apply { addView(list) }
            lateinit var dialog: AlertDialog

            fun save(updated: List<StartupService>) {
                services = updated
                lifecycleScope.launch {
                    local.updateInstalled(distro.copy(startupServices = services))
                    showTemporaryRootfsStatus("Startup services for ${distro.title} saved. Restart this distribution to apply them.")
                }
            }
            fun render() {
                list.removeAllViews()
                if (services.isEmpty()) {
                    list.addView(TextView(this@MainActivity).apply {
                        text = "No startup services yet. Add a shell command to run once after a LinuxDroid session starts."
                        setTextColor(getColor(R.color.ld_muted))
                        setPadding(0, dp(12), 0, dp(12))
                    })
                    return
                }
                services.forEach { service ->
                    val card = MaterialCardView(this@MainActivity).apply {
                        radius = dp(16).toFloat()
                        cardElevation = 0f
                        setCardBackgroundColor(getColor(R.color.ld_surface_alt))
                        strokeColor = getColor(R.color.ld_outline)
                        strokeWidth = dp(1)
                    }
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    }
                    val header = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
                    header.addView(TextView(this@MainActivity).apply {
                        text = service.name
                        typeface = Typeface.DEFAULT_BOLD
                        textSize = 15f
                        setTextColor(getColor(R.color.ld_text))
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    header.addView(MaterialSwitch(this@MainActivity).apply {
                        isChecked = service.enabled
                        setOnCheckedChangeListener { _, enabled ->
                            save(services.map { if (it.id == service.id) it.copy(enabled = enabled) else it })
                        }
                    })
                    row.addView(header)
                    row.addView(TextView(this@MainActivity).apply {
                        text = service.command
                        textSize = 12f
                        typeface = Typeface.MONOSPACE
                        setTextColor(getColor(R.color.ld_muted))
                        setPadding(0, dp(5), 0, dp(6))
                    })
                    val actions = LinearLayout(this@MainActivity).apply { gravity = Gravity.END }
                    actions.addView(MaterialButton(this@MainActivity).apply {
                        text = "Edit"
                        isAllCaps = false
                        setOnClickListener { showStartupServiceEditor(service) { updated -> save(services.map { if (it.id == service.id) updated else it }); render() } }
                    })
                    actions.addView(MaterialButton(this@MainActivity).apply {
                        text = "Remove"
                        isAllCaps = false
                        setOnClickListener { save(services.filterNot { it.id == service.id }); render() }
                    })
                    row.addView(actions)
                    card.addView(row)
                    list.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
                }
            }
            dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Startup services · ${distro.title}")
                .setMessage("LinuxDroid runs each enabled command once after ${distro.title} starts. These commands are private to this distribution and replace only the systemd service use case, not Android background services.")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .setPositiveButton("Add service", null)
                .show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                showStartupServiceEditor(null) { created -> save(services + created); render() }
            }
            render()
        }
    }

    private fun showStartupServiceEditor(existing: StartupService?, onSave: (StartupService) -> Unit) {
        fun field(value: String, hint: String) = EditText(this).apply { setText(value); this.hint = hint }
        val name = field(existing?.name.orEmpty(), "Service name")
        val command = field(existing?.command.orEmpty(), "Command, e.g. /usr/local/bin/my-service --daemon").apply { minLines = 3; gravity = Gravity.TOP }
        val enabled = CheckBox(this).apply { text = "Enable at session start"; isChecked = existing?.enabled ?: true }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            addView(name); addView(command); addView(enabled)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add startup service" else "Edit startup service")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val commandText = command.text.toString().trim()
                if (commandText.isBlank()) {
                    showTemporaryRootfsStatus("Startup service command cannot be empty.")
                } else {
                    onSave(StartupService(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        name = name.text.toString().trim().ifBlank { "Startup command" },
                        command = commandText,
                        enabled = enabled.isChecked
                    ))
                }
            }.show()
    }

    private fun configureVnc() {
        lifecycleScope.launch {
            val current = local.settings()
            val profile = current.vnc
            fun field(value: String, hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT) = EditText(this@MainActivity).apply {
                setText(value)
                this.hint = hint
                this.inputType = inputType
            }
            fun label(value: String) = TextView(this@MainActivity).apply {
                text = value
                setPadding(0, dp(12), 0, dp(2))
            }
            fun selector(items: List<String>, selected: Int) = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
                setSelection(selected)
            }
            val host = field(profile.host, "Host (normally 127.0.0.1)")
            val port = field(profile.port.toString(), "Port", InputType.TYPE_CLASS_NUMBER)
            val password = field(profile.password, "VNC password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            val inputMode = selector(listOf("Touchpad", "Direct touch"), if (profile.inputMode == VncInputMode.TOUCHPAD) 0 else 1)
            val scaling = selector(listOf("Fit to screen", "One-to-one, pinch to zoom"), if (profile.scalingMode == VncScalingMode.FIT) 0 else 1)
            val readOnly = CheckBox(this@MainActivity).apply { text = "View only"; isChecked = profile.viewOnly }
            val controls = CheckBox(this@MainActivity).apply { text = "Show on-screen controls"; isChecked = profile.showOnScreenControls }
            val landscape = CheckBox(this@MainActivity).apply { text = "Force landscape while connected"; isChecked = profile.forceLandscape }
            val gamepad = CheckBox(this@MainActivity).apply { text = "Enable floating virtual gamepad"; isChecked = profile.floatingGamepadEnabled }
            val invertGamepad = CheckBox(this@MainActivity).apply { text = "Invert virtual gamepad arrows"; isChecked = profile.invertGamepadDpad }
            val gamepadOpacity = field(profile.floatingGamepadOpacity.toString(), "Gamepad opacity (25-90%)", InputType.TYPE_CLASS_NUMBER)
            val keepAwake = CheckBox(this@MainActivity).apply { text = "Keep screen awake while connected"; isChecked = profile.keepScreenAwake }
            val form = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), 0, dp(24), 0)
                addView(host); addView(port); addView(password)
                addView(label("Input mode")); addView(inputMode)
                addView(label("Scaling")); addView(scaling)
                addView(readOnly); addView(controls); addView(landscape); addView(gamepad); addView(invertGamepad)
                addView(label("Floating gamepad appearance")); addView(gamepadOpacity)
                addView(keepAwake)
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Internal VNC connection")
                .setMessage("LinuxDroid starts the desktop itself. Set only the connection and viewer behavior here; localhost is recommended for built-in desktops.")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ ->
                    lifecycleScope.launch {
                        val validPort = port.text.toString().toIntOrNull()?.takeIf { it in 1..65535 }
                        if (validPort == null) { showTemporaryRootfsStatus("VNC port must be between 1 and 65535."); return@launch }
                        val opacity = gamepadOpacity.text.toString().toIntOrNull()?.coerceIn(25, 90) ?: profile.floatingGamepadOpacity
                        val updated = VncProfile(
                            host = host.text.toString().trim().ifBlank { "127.0.0.1" },
                            port = validPort,
                            password = password.text.toString(),
                            colorDepth = profile.colorDepth,
                            viewOnly = readOnly.isChecked,
                            inputMode = if (inputMode.selectedItemPosition == 0) VncInputMode.TOUCHPAD else VncInputMode.DIRECT_TOUCH,
                            scalingMode = if (scaling.selectedItemPosition == 0) VncScalingMode.FIT else VncScalingMode.ONE_TO_ONE,
                            showOnScreenControls = controls.isChecked,
                            forceLandscape = landscape.isChecked,
                            floatingGamepadEnabled = gamepad.isChecked,
                            invertGamepadDpad = invertGamepad.isChecked,
                            floatingGamepadOpacity = opacity,
                            gamepadButtons = profile.gamepadButtons,
                            keepScreenAwake = keepAwake.isChecked
                        )
                        local.saveSettings(current.copy(vnc = updated))
                        showTemporaryRootfsStatus("VNC settings saved.")
                    }
                }.show()
        }
    }

    private fun showSessionLog() {
        val logStore = SessionLogStore(local)
        val output = TextView(this).apply {
            text = logStore.read()
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(getColor(R.color.ld_text))
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(output) }
        AlertDialog.Builder(this)
            .setTitle("Latest session diagnostics")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .setNeutralButton("Clear") { _, _ ->
                logStore.clear()
                showTemporaryRootfsStatus("Session log cleared.")
            }
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("LinuxDroid session log", output.text))
                showTemporaryRootfsStatus("Session log copied to clipboard.")
            }
            .show()
    }

    private fun showReleaseDistributions() {
        if (releaseDistributions.isEmpty()) {
            lifecycleScope.launch {
                loadReleaseSilently()
                if (releaseDistributions.isEmpty()) showTemporaryRootfsStatus("The LinuxDroid RootFS release is not available yet.")
                else showReleaseDistributions()
            }
            return
        }
        val abi = RuntimeInstaller(this, local).supportedAbi()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        lateinit var dialog: AlertDialog
        releaseDistributions.forEach { definition ->
            val available = definition.architectures.containsKey(abi)
            val accent = distroColor(definition.title)
            val card = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(getColor(if (available) R.color.ld_surface else R.color.ld_surface_alt))
                strokeColor = getColor(if (available) R.color.ld_outline else R.color.ld_muted)
                strokeWidth = dp(1)
                isEnabled = available
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(15), dp(13), dp(15), dp(13))
                    addView(TextView(this@MainActivity).apply {
                        text = "${definition.title}  ${definition.version}"
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(getColor(R.color.ld_text))
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = if (available) "Available for $abi · ${definition.description}" else "Not published for $abi"
                        textSize = 12f
                        setPadding(0, dp(5), 0, 0)
                        setTextColor(getColor(R.color.ld_muted))
                    })
                })
                setOnClickListener {
                    if (available) {
                        dialog.dismiss()
                        install(definition)
                    }
                }
            }
            list.addView(card, linearParams(bottom = 9))
            list.addView(View(this).apply { setBackgroundColor(accent) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)).apply { setMargins(dp(12), 0, dp(12), dp(9)) })
        }
        val scroll = ScrollView(this).apply { addView(list) }
        dialog = AlertDialog.Builder(this)
            .setTitle("Choose a distribution")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun install(definition: RootfsDefinition) {
        lifecycleScope.launch {
            runCatching {
                val abi = RuntimeInstaller(this@MainActivity, local).supportedAbi()
                val artifact = definition.architectures[abi] ?: error("${definition.title} does not provide a $abi RootFS.")
                status.text = "Downloading ${definition.title}…"
                val suffix = artifact.url.substringAfterLast('/').substringAfterLast('.', "tar")
                val archive = File(local.downloadsDirectory(), "${definition.id}-${definition.version}-$abi.$suffix")
                network.downloadVerified(artifact, archive) { received, total ->
                    runOnUiThread {
                        status.text = if (total > 0) "Downloading ${definition.title}: ${received * 100 / total}%" else "Downloading ${definition.title}: $received bytes"
                    }
                }
                status.text = "Extracting ${definition.title}…"
                RootfsInstaller(local).install(definition, abi, archive) { count -> runOnUiThread { status.text = "Extracting ${definition.title}: $count files" } }
            }.onSuccess { installed ->
                showTemporaryRootfsStatus("Installed ${installed.title}.")
                refreshInstalled()
            }.onFailure { error -> showTemporaryRootfsStatus("Installation failed: ${error.message}") }
        }
    }

    private fun refreshInstalled() {
        if (!::installedContainer.isInitialized) return
        lifecycleScope.launch {
            val installed = local.listInstalled()
            if (!::installedContainer.isInitialized) return@launch
            installedContainer.removeAllViews()
            if (installed.isEmpty()) {
                installedContainer.addView(emptyStateCard())
            } else installed.forEachIndexed { index, distro ->
                installedContainer.addView(distributionRow(distro), linearParams(bottom = if (index == installed.lastIndex) 0 else 12))
            }
        }
    }

    private fun emptyStateCard(): View = MaterialCardView(this).apply {
        radius = dp(22).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(getColor(R.color.ld_surface_alt))
        strokeColor = getColor(R.color.ld_outline)
        strokeWidth = dp(1)
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(TextView(this@MainActivity).apply {
                text = "No distributions installed yet"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.ld_text))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Choose Install distro to download a verified RootFS directly from the LinuxDroid release."
                textSize = 14f
                setPadding(0, dp(6), 0, 0)
                setTextColor(getColor(R.color.ld_muted))
            })
        })
    }

    private fun distributionRow(distro: InstalledDistro): View {
        val accent = distroColor(distro.title)
        return MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(getColor(R.color.ld_surface))
            strokeColor = getColor(R.color.ld_outline)
            strokeWidth = dp(1)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(View(this@MainActivity).apply {
                    background = roundedColor(accent, 24)
                }, LinearLayout.LayoutParams(dp(7), LinearLayout.LayoutParams.MATCH_PARENT))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(16), dp(12), dp(16))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(TextView(this@MainActivity).apply {
                            text = distro.title
                            textSize = 19f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(getColor(R.color.ld_text))
                        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        addView(TextView(this@MainActivity).apply {
                            text = distro.architecture
                            textSize = 11f
                            typeface = Typeface.DEFAULT_BOLD
                            setPadding(dp(9), dp(5), dp(9), dp(5))
                            setTextColor(getColor(R.color.ld_badge_text))
                            background = roundedColor(accent, 12)
                        })
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "${distro.version} · " + (distro.setup?.let { "${it.desktop} desktop · ${it.browser}" } ?: "Desktop not configured")
                        textSize = 13f
                        setPadding(0, dp(5), 0, 0)
                        setTextColor(getColor(R.color.ld_muted))
                    })
                    val isRunning = LinuxRuntime.controller(this@MainActivity).activeDistro?.installId == distro.installId
                    fun actionRow() = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                    }
                    fun actionParams(top: Int, end: Int) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(0, dp(top), dp(end), 0)
                    }
                    val firstRow = actionRow()
                    firstRow.addView(
                        actionButton(if (isRunning) "Stop" else "Start", if (isRunning) ActionTone.DANGER else ActionTone.SUCCESS) {
                            if (isRunning) stopSession(distro) else startSession(distro)
                        },
                        actionParams(top = 14, end = 7)
                    )
                    firstRow.addView(actionButton("Terminal", ActionTone.INFO) { startActivity(Intent(this@MainActivity, TerminalActivity::class.java)) }, actionParams(top = 14, end = 7))
                    firstRow.addView(actionButton("VNC", ActionTone.VIOLET) { startActivity(Intent(this@MainActivity, VncActivity::class.java)) }, actionParams(top = 14, end = 0))
                    val secondRow = actionRow()
                    secondRow.addView(actionButton("Setup", ActionTone.WARNING) { showSetup(distro) }, actionParams(top = 8, end = 7))
                    secondRow.addView(actionButton("Services", ActionTone.PRIMARY) { configureStartupServices(distro) }, actionParams(top = 8, end = 7))
                    secondRow.addView(actionButton("Remove", ActionTone.DANGER) { removeDistro(distro) }, actionParams(top = 8, end = 0))
                    addView(firstRow)
                    addView(secondRow)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
    }

    private fun startSession(distro: InstalledDistro) {
        val controller = LinuxRuntime.controller(this)
        val active = controller.activeDistro
        if (active != null && active.installId != distro.installId) {
            showTemporaryRootfsStatus("Stop ${active.title} before starting another distribution.")
            return
        }
        if (active?.installId == distro.installId) {
            startActivity(Intent(this, TerminalActivity::class.java))
            return
        }
        LinuxSessionService.start(this, distro.installId)
        showTemporaryRootfsStatus("Starting ${distro.title} in a foreground session…")
        lifecycleScope.launch {
            repeat(32) {
                delay(250)
                if (controller.activeDistro?.installId == distro.installId && controller.session != null) {
                    showTemporaryRootfsStatus("${distro.title} is running. Opening terminal…")
                    refreshInstalled()
                    startActivity(Intent(this@MainActivity, TerminalActivity::class.java))
                    return@launch
                }
            }
            showTemporaryRootfsStatus("${distro.title} is still starting. Check the foreground notification or Session diagnostics.")
            refreshInstalled()
        }
    }

    private fun stopSession(distro: InstalledDistro) {
        LinuxSessionService.stop(this)
        showTemporaryRootfsStatus("Stopping ${distro.title}…")
        lifecycleScope.launch {
            delay(500)
            refreshInstalled()
        }
    }

    private fun showSessionControls() {
        val running = LinuxRuntime.controller(this).activeDistro
        AlertDialog.Builder(this)
            .setTitle("Linux session")
            .setMessage(running?.let { "Running: ${it.title}. Keep the ongoing notification visible and disable battery optimization for long sessions." } ?: "No session is currently running.")
            .setNegativeButton("Close", null)
            .setPositiveButton(if (running != null) "Stop session" else "Open Android battery settings") { _, _ ->
                if (running != null) LinuxSessionService.stop(this)
                else startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            .show()
    }

    private fun removeDistro(distro: InstalledDistro) {
        if (LinuxRuntime.controller(this).activeDistro?.installId == distro.installId) {
            showTemporaryRootfsStatus("Stop this distribution before removing it.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Remove ${distro.title}?")
            .setMessage("This permanently deletes the RootFS and all data inside it.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    File(distro.rootfsDirectory).deleteRecursively()
                    local.removeInstalled(distro.installId)
                    refreshInstalled()
                }
            }.show()
    }

    private fun showSetup(distro: InstalledDistro) {
        val desktop = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, DesktopEnvironment.entries.map { it.name }) }
        val browser = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, BrowserChoice.entries.map { it.name }) }
        val media = CheckBox(this).apply { text = "Install media and text utilities" }
        val password = EditText(this).apply { hint = "VNC password (recommended)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, dp(24), 0)
            addView(TextView(this@MainActivity).apply { text = "Desktop" }); addView(desktop)
            addView(TextView(this@MainActivity).apply { text = "Browser" }); addView(browser)
            addView(media); addView(password)
        }
        AlertDialog.Builder(this)
            .setTitle("Desktop setup: ${distro.title}")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install") { _, _ ->
                val selection = SetupSelection(
                    desktop = DesktopEnvironment.valueOf(desktop.selectedItem.toString()),
                    browser = BrowserChoice.valueOf(browser.selectedItem.toString()),
                    mediaAndTextTools = media.isChecked,
                    createVncPassword = password.text.toString().takeIf { it.isNotBlank() }
                )
                applySetup(distro, selection)
            }.show()
    }

    private fun applySetup(distro: InstalledDistro, selection: SetupSelection) {
        lifecycleScope.launch {
            startSession(distro)
            for (attempt in 0 until 24) {
                if (LinuxRuntime.controller(this@MainActivity).activeDistro?.installId == distro.installId) break
                delay(500)
            }
            val controller = LinuxRuntime.controller(this@MainActivity)
            if (controller.activeDistro?.installId != distro.installId) {
                showTemporaryRootfsStatus("Could not start the session to run desktop setup.")
                return@launch
            }
            val script = DesktopSetupBuilder().build(selection)
            controller.session?.write("cat > /tmp/linuxdroid-setup.sh <<'LINUXDROID_SETUP'\n$script" + "LINUXDROID_SETUP\nsh /tmp/linuxdroid-setup.sh\n")
            local.updateInstalled(distro.copy(setup = selection))
            if (!selection.createVncPassword.isNullOrBlank()) {
                val settings = local.settings()
                local.saveSettings(settings.copy(vnc = settings.vnc.copy(password = selection.createVncPassword)))
            }
            showTemporaryRootfsStatus("Desktop setup was sent to ${distro.title}. Open Terminal to monitor installation, then run ~/start-linuxdroid-desktop and open VNC.")
            refreshInstalled()
        }
    }

    private fun showTemporaryRootfsStatus(message: String) {
        statusRefreshJob?.cancel()
        status.text = message
        statusRefreshJob = lifecycleScope.launch {
            delay(6_000)
            loadReleaseSilently()
        }
    }

    private suspend fun loadReleaseSilently() {
        runCatching { releaseClient.fetchDistributions() }
            .onSuccess { loaded ->
                releaseDistributions = loaded
                if (::installButton.isInitialized) installButton.isEnabled = loaded.isNotEmpty()
                status.text = "RootFS 1 online · ${loaded.size} distribution(s) available."
            }
            .onFailure {
                if (::installButton.isInitialized) installButton.isEnabled = false
                status.text = "RootFS 1 error: ${it.message}"
            }
    }

    private fun distroColor(value: String): Int = getColor(
        when {
            value.contains("ubuntu", true) || value.contains("debian", true) -> R.color.ld_distro_orange
            value.contains("arch", true) || value.contains("manjaro", true) -> R.color.ld_distro_blue
            value.contains("fedora", true) || value.contains("rocky", true) || value.contains("alma", true) -> R.color.ld_distro_cyan
            value.contains("alpine", true) || value.contains("void", true) -> R.color.ld_distro_green
            else -> R.color.ld_distro_pink
        }
    )

    private fun roundedGradient(start: Int, middle: Int, end: Int, radius: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(getColor(start), getColor(middle), getColor(end))
    ).apply { cornerRadius = dp(radius).toFloat() }

    private fun roundedColor(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun linearParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(top), 0, dp(bottom)) }

    private fun weightParams(weight: Float, top: Int = 0, start: Int = 0, end: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        weight
    ).apply { setMargins(dp(start), dp(top), dp(end), 0) }

    private fun wrapParams(top: Int = 0, end: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(top), dp(end), 0) }
}
