package io.linuxdroid.app.engine

import com.termux.terminal.TerminalSession
import io.linuxdroid.app.data.LocalRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent, privacy-local diagnostics for the most recent Linux session. */
class SessionLogStore(private val local: LocalRepository) {
    private val file: File get() = File(local.logsDirectory(), "last-session.log")

    @Synchronized
    fun begin(distroTitle: String, rootfsDirectory: File, launch: ProotLaunch) {
        val guestShell = File(rootfsDirectory, "bin/sh")
        file.writeText(
            buildString {
                appendLine("LinuxDroid session diagnostics")
                appendLine("Started: ${timestamp()}")
                appendLine("Distribution: $distroTitle")
                appendLine("RootFS: ${rootfsDirectory.absolutePath}")
                appendLine("Guest shell: ${guestShell.absolutePath}")
                appendLine("Guest shell exists: ${guestShell.isFile}")
                appendLine("Guest shell executable: ${guestShell.canExecute()}")
                appendLine("Host shell: ${launch.shellPath}")
                appendLine("Host command: ${launch.arguments.joinToString(" ")}")
                appendLine("Environment: ${launch.environment.joinToString(" ")}")
                appendLine("--- terminal output ---")
            }
        )
    }

    @Synchronized
    fun recordStartFailure(error: Throwable) {
        append("Start failure at ${timestamp()}: ${error.javaClass.simpleName}: ${error.message}\n")
    }

    @Synchronized
    fun recordExit(session: TerminalSession, exitCode: Int) {
        val transcript = runCatching {
            session.emulator.screen.transcriptText
        }.getOrElse { "<Terminal transcript unavailable: ${it.message}>" }
        append("\n--- terminal transcript at exit ---\n")
        append(transcript.takeLast(MAX_TRANSCRIPT_CHARS))
        append("\n--- session ended at ${timestamp()} with exit code $exitCode ---\n")
    }

    @Synchronized
    fun read(): String = runCatching { file.readText() }.getOrDefault("No Linux session log has been recorded yet.")

    @Synchronized
    fun clear() {
        file.delete()
    }

    @Synchronized
    private fun append(text: String) {
        file.parentFile?.mkdirs()
        file.appendText(text)
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private companion object {
        const val MAX_TRANSCRIPT_CHARS = 48_000
    }
}
