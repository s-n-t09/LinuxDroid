package io.linuxdroid.app.engine

import io.linuxdroid.app.data.LocalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class PulseAudioController(private val local: LocalRepository) {
    private var process: Process? = null

    suspend fun start(runtime: RuntimeInstaller.RuntimePaths): Boolean = withContext(Dispatchers.IO) {
        val binary = runtime.pulseBinary ?: return@withContext false
        if (process?.isAlive == true && isListening()) return@withContext true
        stop()

        val configDirectory = File(local.runtimeDirectory(), "pulse-config").apply { mkdirs() }
        val logFile = File(local.logsDirectory(), "pulseaudio.log").apply {
            parentFile?.mkdirs()
            writeText("LinuxDroid PulseAudio startup\n")
        }
        val moduleDirectory = runtime.pulseModuleDirectory
            ?: return@withContext logFile.appendText("PulseAudio modules directory is missing.\n").let { false }
        val configuration = File(configDirectory, "default.pa")
        configuration.writeText(
            """
            .nofail
            load-module module-native-protocol-tcp listen=127.0.0.1 port=4713 auth-anonymous=1 auth-ip-acl=127.0.0.1
            load-module module-sles-sink sink_name=linuxdroid_output
            set-default-sink linuxdroid_output
            """.trimIndent() + "\n"
        )
        File(configDirectory, "daemon.conf").writeText(
            """
            exit-idle-time = -1
            flat-volumes = no
            resample-method = trivial
            log-time = yes
            """.trimIndent() + "\n"
        )
        val command = listOf(
            binary.absolutePath,
            "--daemonize=no",
            "--system=false",
            "--exit-idle-time=-1",
            "--log-target=stderr",
            "--log-level=info",
            "--config-file=${configuration.absolutePath}"
        )
        val builder = ProcessBuilder(command)
            .directory(configDirectory)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        builder.environment().apply {
            this["HOME"] = configDirectory.absolutePath
            this["TMPDIR"] = File(configDirectory, "tmp").apply { mkdirs() }.absolutePath
            this["PULSE_RUNTIME_PATH"] = File(configDirectory, "run").apply { mkdirs() }.absolutePath
            this["PULSE_STATE_PATH"] = File(configDirectory, "state").apply { mkdirs() }.absolutePath
            this["PULSE_DLPATH"] = moduleDirectory.absolutePath
            runtime.pulseLibraryDirectory?.let { this["LD_LIBRARY_PATH"] = it.absolutePath }
        }
        logFile.appendText("Command: ${command.joinToString(" ")}\nModules: ${moduleDirectory.absolutePath}\n")
        process = runCatching { builder.start() }.onFailure { error ->
            logFile.appendText("Process start failed: ${error.message}\n")
        }.getOrNull()

        repeat(20) {
            if (process?.isAlive != true) return@repeat
            if (isListening()) {
                logFile.appendText("PulseAudio is listening on 127.0.0.1:4713.\n")
                return@withContext true
            }
            Thread.sleep(150)
        }
        logFile.appendText("PulseAudio did not become ready. alive=${process?.isAlive}\n")
        false
    }

    private fun isListening(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 4713), 250)
        }
        true
    }.getOrDefault(false)

    suspend fun stop() = withContext(Dispatchers.IO) {
        process?.let { running ->
            if (running.isAlive) {
                running.destroy()
                if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly()
            }
        }
        process = null
    }
}
