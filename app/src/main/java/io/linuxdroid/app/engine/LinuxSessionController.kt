package io.linuxdroid.app.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.linuxdroid.app.data.AppSettings
import io.linuxdroid.app.data.InstalledDistro
import io.linuxdroid.app.data.LocalRepository
import io.linuxdroid.app.data.RootfsLayout
import io.linuxdroid.app.data.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Hosts one active distribution and one or more independent interactive PRoot shells.
 * The tabs share a RootFS and PulseAudio daemon, but every tab gets its own process and
 * TerminalSession so closing a tab never terminates its neighbours.
 */
class LinuxSessionController(context: Context) : TerminalSessionClient {
    private val appContext = context.applicationContext
    private val local = LocalRepository(appContext)
    private val runtimeInstaller = RuntimeInstaller(appContext, local)
    private val prootCommands = ProotCommandFactory()
    private val pulseAudio = PulseAudioController(local)
    private val sessionLog = SessionLogStore(local)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val terminalSessions = LinkedHashMap<String, TerminalSession>()

    private var runtime: RuntimeInstaller.RuntimePaths? = null
    private var activeSettings: AppSettings? = null
    private var nextTabNumber = 1

    private val _state = MutableStateFlow(SessionState.STOPPED)
    val state: StateFlow<SessionState> = _state

    /** Compatibility accessor for the foreground service and legacy callers. */
    val session: TerminalSession?
        get() = terminalSessions.values.firstOrNull()

    val sessions: List<TerminalSession>
        get() = terminalSessions.values.toList()

    var activeDistro: InstalledDistro? = null
        private set
    var onTerminalChanged: ((TerminalSession) -> Unit)? = null
    var onSessionEnded: ((Int) -> Unit)? = null

    suspend fun start(distro: InstalledDistro): TerminalSession = mutex.withLock {
        check(_state.value == SessionState.STOPPED || _state.value == SessionState.FAILED) {
            "Only one Linux distribution may run at a time. Stop the current distribution first."
        }
        _state.value = SessionState.STARTING
        try {
            val settings = local.settings()
            val rootfs = File(distro.rootfsDirectory)
            RootfsLayout.normalizeTopLevelDirectory(rootfs)
            RootfsLayout.rebaseGuestAbsoluteSymlinks(rootfs)
            RootfsLayout.requireGuestShell(rootfs)

            val preparedRuntime = runtimeInstaller.ensureInstalled()
            activeDistro = distro
            activeSettings = settings
            runtime = preparedRuntime
            nextTabNumber = 1
            sessionLog.begin(distro.title, rootfs, prootCommands.createInteractiveLaunch(distro, preparedRuntime, settings))
            if (settings.pulseAudioEnabled) {
                val pulseReady = pulseAudio.start(preparedRuntime)
                sessionLog.recordRuntimeStatus(
                    if (pulseReady) {
                        "PulseAudio is ready on tcp:127.0.0.1:4713."
                    } else {
                        "PulseAudio did not become ready. Recent PulseAudio log:\n${pulseAudio.recentLog().ifBlank { "<no PulseAudio log was written>" }}"
                    }
                )
            } else {
                sessionLog.recordRuntimeStatus("PulseAudio is disabled in LinuxDroid settings.")
            }

            val first = createTerminalLocked(distro, preparedRuntime, settings)
            _state.value = SessionState.RUNNING
            first
        } catch (error: Throwable) {
            sessionLog.recordStartFailure(error)
            clearRuntimeStateLocked()
            _state.value = SessionState.FAILED
            pulseAudio.stop()
            throw error
        }
    }

    /** Opens another shell in the currently running distribution. */
    suspend fun openAdditionalSession(): TerminalSession = mutex.withLock {
        check(_state.value == SessionState.RUNNING) { "Start a Linux distribution before opening another terminal tab." }
        val distro = activeDistro ?: error("No Linux distribution is active.")
        val preparedRuntime = runtime ?: error("Linux runtime is unavailable.")
        val settings = activeSettings ?: local.settings()
        createTerminalLocked(distro, preparedRuntime, settings)
    }

    suspend fun closeSession(target: TerminalSession) = mutex.withLock {
        if (terminalSessions.containsKey(target.mHandle)) target.finishIfRunning()
    }

    suspend fun runGuestCommand(command: String) {
        val running = session ?: error("No Linux session is running.")
        running.write(command.trimEnd() + "\n")
    }

    suspend fun stop() = mutex.withLock {
        if (_state.value == SessionState.STOPPED) return@withLock
        _state.value = SessionState.STOPPING
        terminalSessions.values.toList().forEach { it.finishIfRunning() }
        terminalSessions.clear()
        clearRuntimeStateLocked()
        pulseAudio.stop()
        _state.value = SessionState.STOPPED
    }

    private fun createTerminalLocked(
        distro: InstalledDistro,
        preparedRuntime: RuntimeInstaller.RuntimePaths,
        settings: AppSettings
    ): TerminalSession {
        val tabNumber = nextTabNumber++
        val launch = prootCommands.createInteractiveLaunch(distro, preparedRuntime, settings)
        return TerminalSession(
            launch.shellPath,
            launch.workingDirectory,
            launch.arguments,
            launch.environment,
            10_000,
            this
        ).also { terminal ->
            terminal.mSessionName = "Shell $tabNumber"
            terminal.updateSize(80, 24)
            terminalSessions[terminal.mHandle] = terminal
        }
    }

    private fun clearRuntimeStateLocked() {
        activeDistro = null
        activeSettings = null
        runtime = null
        nextTabNumber = 1
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        onTerminalChanged?.invoke(changedSession)
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        scope.launch {
            mutex.withLock {
                if (!terminalSessions.containsKey(finishedSession.mHandle)) return@withLock
                terminalSessions.remove(finishedSession.mHandle)
                val code = finishedSession.exitStatus
                sessionLog.recordExit(finishedSession, code)
                onTerminalChanged?.invoke(finishedSession)

                if (terminalSessions.isEmpty()) {
                    clearRuntimeStateLocked()
                    pulseAudio.stop()
                    _state.value = if (code == 0) SessionState.STOPPED else SessionState.FAILED
                    onSessionEnded?.invoke(code)
                }
            }
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("LinuxDroid terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString().orEmpty()
        session?.write(text)
    }

    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Terminal error", e) }
}
