package io.linuxdroid.app

import android.Manifest
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
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import io.linuxdroid.app.data.BrowserChoice
import io.linuxdroid.app.data.DesktopEnvironment
import io.linuxdroid.app.data.InstalledDistro
import io.linuxdroid.app.data.LocalRepository
import io.linuxdroid.app.data.RootfsDefinition
import io.linuxdroid.app.data.RootfsInstaller
import io.linuxdroid.app.data.RootfsNetwork
import io.linuxdroid.app.data.RootfsReleaseClient
import io.linuxdroid.app.data.SetupSelection
import io.linuxdroid.app.data.VncProfile
import io.linuxdroid.app.domain.DesktopSetupBuilder
import io.linuxdroid.app.engine.LinuxRuntime
import io.linuxdroid.app.engine.RuntimeInstaller
import io.linuxdroid.app.service.LinuxSessionService
import io.linuxdroid.app.ui.TerminalActivity
import io.linuxdroid.app.vnc.VncActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

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
    private lateinit var installedContainer: LinearLayout
    private lateinit var status: TextView
    private lateinit var installButton: MaterialButton
    private var releaseDistributions: List<RootfsDefinition> = emptyList()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        content.addView(buildHero(), linearParams(top = 0, bottom = 18))
        content.addView(buildStatusCard(), linearParams(bottom = 16))

        val commandTitle = sectionTitle("Command centre", "Your Linux environments, in one place")
        content.addView(commandTitle, linearParams(bottom = 10))

        val actionGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val primaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        installButton = actionButton("Install distro", ActionTone.PRIMARY) { showReleaseDistributions() }
        primaryRow.addView(installButton, weightParams(1f, end = 8))
        primaryRow.addView(actionButton("Refresh", ActionTone.INFO) { lifecycleScope.launch { loadReleaseSilently() } }, weightParams(1f, start = 8))
        actionGrid.addView(primaryRow)

        val toolsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolsRow.addView(actionButton("VNC settings", ActionTone.VIOLET) { configureVnc() }, weightParams(1f, top = 10, end = 8))
        toolsRow.addView(actionButton("Session", ActionTone.WARNING) { showSessionControls() }, weightParams(1f, top = 10, start = 8))
        actionGrid.addView(toolsRow)
        content.addView(actionGrid, linearParams(bottom = 26))

        content.addView(sectionTitle("Installed distributions", "Only one Linux session runs at a time"), linearParams(bottom = 10))
        installedContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(installedContainer)

        return ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.ld_background))
            isFillViewport = true
            addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
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
                    text = "RootFS release status"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(getColor(R.color.ld_muted))
                })
                status = TextView(this@MainActivity).apply {
                    text = "Loading LinuxDroid RootFS release…"
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
        minHeight = dp(48)
        cornerRadius = dp(16)
        insetTop = 0
        insetBottom = 0
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
                .setMessage("LinuxDroid can bind shared storage at /sdcard inside Linux only if you grant All files access. This is optional; without it the distribution remains isolated. You can change this later in the source/settings dialog.")
                .setNegativeButton("Keep isolated", null)
                .setPositiveButton("Allow and open settings") { _, _ -> requestAllFilesAccess() }
                .show()
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun configureVnc() {
        lifecycleScope.launch {
            val current = local.settings()
            val profile = current.vnc
            fun field(value: String, hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT) = EditText(this@MainActivity).apply {
                setText(value); this.hint = hint; this.inputType = inputType
            }
            val host = field(profile.host, "Host (normally 127.0.0.1)")
            val port = field(profile.port.toString(), "Port", InputType.TYPE_CLASS_NUMBER)
            val password = field(profile.password, "VNC password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            val command = field(profile.desktopCommand, "Desktop command")
            val readOnly = CheckBox(this@MainActivity).apply { text = "View only"; isChecked = profile.viewOnly }
            val scale = CheckBox(this@MainActivity).apply { text = "Scale desktop to fit"; isChecked = profile.scaleToFit }
            val form = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, dp(24), 0)
                addView(host); addView(port); addView(password); addView(command); addView(readOnly); addView(scale)
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Internal VNC connection")
                .setMessage("For a desktop started by LinuxDroid, keep the host at 127.0.0.1. Never expose VNC to a public network without a secure tunnel.")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ ->
                    lifecycleScope.launch {
                        val validPort = port.text.toString().toIntOrNull()?.takeIf { it in 1..65535 }
                        if (validPort == null) { status.text = "VNC port must be between 1 and 65535."; return@launch }
                        val updated = VncProfile(
                            host = host.text.toString().trim().ifBlank { "127.0.0.1" },
                            port = validPort,
                            password = password.text.toString(),
                            colorDepth = profile.colorDepth,
                            viewOnly = readOnly.isChecked,
                            scaleToFit = scale.isChecked,
                            desktopCommand = command.text.toString().trim().ifBlank { "startxfce4" }
                        )
                        local.saveSettings(current.copy(vnc = updated))
                        status.text = "VNC settings saved."
                    }
                }.show()
        }
    }

    private fun showReleaseDistributions() {
        if (releaseDistributions.isEmpty()) {
            lifecycleScope.launch {
                loadReleaseSilently()
                if (releaseDistributions.isEmpty()) status.text = "The LinuxDroid RootFS release is not available yet."
                else showReleaseDistributions()
            }
            return
        }
        val abi = RuntimeInstaller(this, local).supportedAbi()
        AlertDialog.Builder(this)
            .setTitle("Choose a distribution")
            .setItems(releaseDistributions.map { definition ->
                val availability = if (definition.architectures.containsKey(abi)) "Available for $abi" else "Not published for $abi"
                "${definition.title} ${definition.version}\n$availability · ${definition.description}"
            }.toTypedArray()) { _, index -> install(releaseDistributions[index]) }
            .show()
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
                status.text = "Installed ${installed.title}."
                refreshInstalled()
            }.onFailure { error -> status.text = "Installation failed: ${error.message}" }
        }
    }

    private fun refreshInstalled() {
        lifecycleScope.launch {
            val installed = local.listInstalled()
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
                    val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
                    actions.addView(actionButton("Start", ActionTone.SUCCESS) { startSession(distro) }, wrapParams(top = 14, end = 8))
                    actions.addView(actionButton("Terminal", ActionTone.INFO) { startActivity(Intent(this@MainActivity, TerminalActivity::class.java)) }, wrapParams(top = 14, end = 8))
                    actions.addView(actionButton("VNC", ActionTone.VIOLET) { startActivity(Intent(this@MainActivity, VncActivity::class.java)) }, wrapParams(top = 14, end = 8))
                    actions.addView(actionButton("Setup", ActionTone.WARNING) { showSetup(distro) }, wrapParams(top = 14, end = 8))
                    actions.addView(actionButton("Remove", ActionTone.DANGER) { removeDistro(distro) }, wrapParams(top = 14))
                    addView(HorizontalScrollView(this@MainActivity).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(actions)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
    }

    private fun startSession(distro: InstalledDistro) {
        val active = LinuxRuntime.controller(this).activeDistro
        if (active != null && active.installId != distro.installId) {
            status.text = "Stop ${active.title} before starting another distribution."
            return
        }
        LinuxSessionService.start(this, distro.installId)
        status.text = "Starting ${distro.title} in a foreground session…"
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
            status.text = "Stop this distribution before removing it."
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
                status.text = "Could not start the session to run desktop setup."
                return@launch
            }
            val script = DesktopSetupBuilder().build(selection)
            controller.session?.write("cat > /tmp/linuxdroid-setup.sh <<'LINUXDROID_SETUP'\n$script" + "LINUXDROID_SETUP\nsh /tmp/linuxdroid-setup.sh\n")
            local.updateInstalled(distro.copy(setup = selection))
            if (!selection.createVncPassword.isNullOrBlank()) {
                val settings = local.settings()
                local.saveSettings(settings.copy(vnc = settings.vnc.copy(password = selection.createVncPassword)))
            }
            status.text = "Desktop setup was sent to ${distro.title}. Open Terminal to monitor installation, then run ~/start-linuxdroid-desktop and open VNC."
            refreshInstalled()
        }
    }

    private suspend fun loadReleaseSilently() {
        runCatching { releaseClient.fetchDistributions() }
            .onSuccess { loaded ->
                releaseDistributions = loaded
                installButton.isEnabled = loaded.isNotEmpty()
                status.text = "Release online · ${loaded.size} distribution(s) available."
            }
            .onFailure {
                installButton.isEnabled = false
                status.text = "RootFS release error: ${it.message}"
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
