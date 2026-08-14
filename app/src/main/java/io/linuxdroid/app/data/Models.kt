package io.linuxdroid.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RootfsCatalog(
    val schema: Int = 1,
    val generatedAt: String? = null,
    val distributions: List<RootfsDefinition> = emptyList()
)

@Serializable
data class RootfsDefinition(
    val id: String,
    val title: String,
    val version: String,
    val description: String = "",
    val architectures: Map<String, RootfsArtifact>,
    val defaultUser: String = "root",
    val homepage: String? = null
)

@Serializable
data class RootfsArtifact(
    val url: String,
    val sha256: String,
    val sizeBytes: Long = 0L,
    val format: String = "tar.xz"
)

@Serializable
data class InstalledDistro(
    val id: String,
    val title: String,
    val version: String,
    val installId: String,
    val architecture: String,
    val rootfsDirectory: String,
    val installedAtEpochMs: Long,
    val setup: SetupSelection? = null
)

@Serializable
data class SetupSelection(
    val desktop: DesktopEnvironment,
    val browser: BrowserChoice,
    val mediaAndTextTools: Boolean,
    val createVncPassword: String? = null
)

@Serializable
enum class DesktopEnvironment { XFCE, LXDE, MATE, FLUXBOX }

@Serializable
enum class BrowserChoice { FIREFOX, CHROMIUM, NONE }

@Serializable
enum class VncInputMode { DIRECT_TOUCH, TOUCHPAD }

@Serializable
enum class VncScalingMode { FIT, ONE_TO_ONE }

@Serializable
data class VncProfile(
    val host: String = "127.0.0.1",
    val port: Int = 5901,
    val password: String = "",
    val colorDepth: Int = 24,
    val viewOnly: Boolean = false,
    val inputMode: VncInputMode = VncInputMode.TOUCHPAD,
    val scalingMode: VncScalingMode = VncScalingMode.FIT,
    val showOnScreenControls: Boolean = true,
    val keepScreenAwake: Boolean = true
)

@Serializable
data class AppSettings(
    val enableAllFilesBinding: Boolean = false,
    val keepScreenAwake: Boolean = true,
    val pulseAudioEnabled: Boolean = true,
    val vnc: VncProfile = VncProfile()
)

enum class SessionState { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
