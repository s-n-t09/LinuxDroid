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

class LinuxSessionController(context: Context) : TerminalSessionClient {
    private val appContext = context.applicationContext
    private val local = LocalRepository(appContext)
    private val runtimeInstaller = RuntimeInstaller(appContext, local)
    private val prootCommands = ProotCommandFactory()
    private val pulseAudio = PulseAudioController(local)
    private val sessionLog = SessionLogStore(local)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(SessionState.STOPPED)
    val state: StateFlow<SessionState> = _state
    var session: TerminalSession? = null
        private set
    var activeDistro: InstalledDistro? = null
        private set
    var onTerminalChanged: ((TerminalSession) -> Unit)? = null
    var onSessionEnded: ((Int) -> Unit)? = null

    suspend fun start(distro: InstalledDistro): TerminalSession = mutex.withLock {
        check(_state.value == SessionState.STOPPED || _state.value == SessionState.FAILED) {
            "Only one Linux distribution may run at a time. Stop the current session first."
        }
        _state.value = SessionState.STARTING
        try {
            val settings: AppSettings = local.settings()
            val rootfs = java.io.File(distro.rootfsDirectory)
            RootfsLayout.normalizeTopLevelDirectory(rootfs)
            RootfsLayout.rebaseGuestAbsoluteSymlinks(rootfs)
            RootfsLayout.requireGuestShell(rootfs)
            val runtime = runtimeInstaller.ensureInstalled()
            val launch = prootCommands.createInteractiveLaunch(distro, runtime, settings)
            sessionLog.begin(distro.title, rootfs, launch)
            if (settings.pulseAudioEnabled) pulseAudio.start(runtime)
            return@withLock TerminalSession(
                launch.shellPath,
                launch.workingDirectory,
                launch.arguments,
                launch.environment,
                10_000,
                this
            ).also { terminal ->
                terminal.mSessionName = distro.title
                session = terminal
                activeDistro = distro
                terminal.updateSize(80, 24)
                _state.value = SessionState.RUNNING
            }
        } catch (error: Throwable) {
            sessionLog.recordStartFailure(error)
            _state.value = SessionState.FAILED
            pulseAudio.stop()
            throw error
        }
    }

    suspend fun runGuestCommand(command: String) {
        val running = session ?: error("No Linux session is running.")
        running.write(command.trimEnd() + "\n")
    }

    suspend fun stop() = mutex.withLock {
        if (_state.value == SessionState.STOPPED) return@withLock
        _state.value = SessionState.STOPPING
        session?.finishIfRunning()
        session = null
        activeDistro = null
        pulseAudio.stop()
        _state.value = SessionState.STOPPED
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        onTerminalChanged?.invoke(changedSession)
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        scope.launch {
            mutex.withLock {
                if (session === finishedSession) {
                    val code = finishedSession.exitStatus
                    sessionLog.recordExit(finishedSession, code)
                    session = null
                    activeDistro = null
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
