package io.linuxdroid.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.linuxdroid.app.data.BrowserChoice
import io.linuxdroid.app.data.DesktopEnvironment
import io.linuxdroid.app.data.InstalledDistro
import io.linuxdroid.app.data.LocalRepository
import io.linuxdroid.app.data.RootfsDefinition
import io.linuxdroid.app.data.RootfsInstaller
import io.linuxdroid.app.data.RootfsNetwork
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
    private val local by lazy { LocalRepository(this) }
    private val network = RootfsNetwork()
    private lateinit var installedContainer: LinearLayout
    private lateinit var status: TextView
    private var catalog: List<RootfsDefinition> = emptyList()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        requestFirstRunPermissions()
        refreshInstalled()
        lifecycleScope.launch { loadCatalogSilently() }
    }

    override fun onResume() {
        super.onResume()
        refreshInstalled()
    }

    private fun buildScreen(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val title = TextView(this).apply {
            text = "LinuxDroid"
            textSize = 28f
            setTextColor(getColor(R.color.ld_primary))
        }
        status = TextView(this).apply {
            text = "Ready. Configure your HTTPS RootFS catalog to begin."
            setPadding(0, 12, 0, 12)
        }
        content.addView(title)
        content.addView(status)
        content.addView(actionButton("Configure RootFS source") { configureRootfsSource() })
        content.addView(actionButton("Configure internal VNC") { configureVnc() })
        content.addView(actionButton("Install a distribution") { showCatalog() })
        content.addView(actionButton("Session controls") { showSessionControls() })
        content.addView(TextView(this).apply { text = "Installed distributions"; textSize = 18f; setPadding(0, 28, 0, 8) })
        installedContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(installedContainer)
        return ScrollView(this).apply { addView(content) }
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
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

    private fun configureRootfsSource() {
        lifecycleScope.launch {
            val current = local.settings()
            val input = EditText(this@MainActivity).apply {
                hint = "https://github.com/OWNER/REPO/releases/download/CATALOG/catalog.json"
                setText(current.rootfsManifestUrl)
                inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            }
            val storage = CheckBox(this@MainActivity).apply {
                text = "Bind shared storage at /sdcard for Linux sessions"
                isChecked = current.enableAllFilesBinding && Environment.isExternalStorageManager()
            }
            val column = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 0, 48, 0); addView(input); addView(storage) }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("RootFS source and storage")
                .setView(column)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ ->
                    lifecycleScope.launch {
                        val url = input.text.toString().trim()
                        if (url.isNotEmpty() && !url.startsWith("https://")) {
                            status.text = "RootFS catalog URLs must use HTTPS."
                            return@launch
                        }
                        local.saveSettings(current.copy(rootfsManifestUrl = url, enableAllFilesBinding = storage.isChecked))
                        if (storage.isChecked && !Environment.isExternalStorageManager()) requestAllFilesAccess()
                        loadCatalogSilently()
                    }
                }
                .show()
        }
    }

    private fun configureVnc() {
        lifecycleScope.launch {
            val current = local.settings()
            val profile = current.vnc
            fun field(value: String, hint: String, inputType: Int = android.text.InputType.TYPE_CLASS_TEXT) = EditText(this@MainActivity).apply {
                setText(value); this.hint = hint; this.inputType = inputType
            }
            val host = field(profile.host, "Host (normally 127.0.0.1)")
            val port = field(profile.port.toString(), "Port", android.text.InputType.TYPE_CLASS_NUMBER)
            val password = field(profile.password, "VNC password", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            val command = field(profile.desktopCommand, "Desktop command")
            val readOnly = CheckBox(this@MainActivity).apply { text = "View only"; isChecked = profile.viewOnly }
            val scale = CheckBox(this@MainActivity).apply { text = "Scale desktop to fit"; isChecked = profile.scaleToFit }
            val form = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(48, 0, 48, 0)
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

    private fun showCatalog() {
        if (catalog.isEmpty()) {
            lifecycleScope.launch {
                loadCatalogSilently()
                if (catalog.isEmpty()) status.text = "No catalog is loaded. Configure a valid HTTPS catalog URL first."
                else showCatalog()
            }
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose a distribution")
            .setItems(catalog.map { "${it.title} ${it.version}\n${it.description}" }.toTypedArray()) { _, index -> install(catalog[index]) }
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
                installedContainer.addView(TextView(this@MainActivity).apply { text = "No distributions installed yet." })
            } else installed.forEach { installedContainer.addView(distributionRow(it)) }
        }
    }

    private fun distributionRow(distro: InstalledDistro): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 16, 12, 16)
            setBackgroundColor(getColor(R.color.ld_surface))
        }
        row.addView(TextView(this).apply { text = "${distro.title} ${distro.version} (${distro.architecture})"; textSize = 17f })
        row.addView(TextView(this).apply { text = distro.setup?.let { "Desktop: ${it.desktop}, browser: ${it.browser}" } ?: "Desktop not configured" })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        actions.addView(actionButton("Start") { startSession(distro) })
        actions.addView(actionButton("Terminal") { startActivity(Intent(this, TerminalActivity::class.java)) })
        actions.addView(actionButton("VNC") { startActivity(Intent(this, VncActivity::class.java)) })
        actions.addView(actionButton("Setup") { showSetup(distro) })
        actions.addView(actionButton("Remove") { removeDistro(distro) })
        row.addView(HorizontalScrollView(this).apply { addView(actions) })
        return row
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
        val password = EditText(this).apply { hint = "VNC password (recommended)"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 0, 48, 0)
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

    private suspend fun loadCatalogSilently() {
        val url = local.settings().rootfsManifestUrl
        if (url.isBlank()) return
        runCatching { network.fetchCatalog(url) }
            .onSuccess { loaded ->
                catalog = loaded.distributions
                status.text = "RootFS catalog loaded: ${catalog.size} distribution(s)."
            }
            .onFailure { status.text = "Catalog error: ${it.message}" }
    }
}
