package io.linuxdroid.app.engine

import io.linuxdroid.app.data.LocalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class PulseAudioController(private val local: LocalRepository) {
    private var process: Process? = null

    suspend fun start(runtime: RuntimeInstaller.RuntimePaths): Boolean = withContext(Dispatchers.IO) {
        val binary = runtime.pulseBinary ?: return@withContext false
        if (process?.isAlive == true) return@withContext true
        val configDirectory = File(local.runtimeDirectory(), "pulse-config").apply { mkdirs() }
        val logFile = File(local.logsDirectory(), "pulseaudio.log")
        val configuration = File(configDirectory, "default.pa")
        configuration.writeText(
            """
            .nofail
            load-module module-native-protocol-tcp auth-anonymous=1 auth-ip-acl=127.0.0.1
            load-module module-sles-sink sink_name=linuxdroid_output
            set-default-sink linuxdroid_output
            """.trimIndent() + "\n"
        )
        File(configDirectory, "daemon.conf").writeText(
            "exit-idle-time = -1\nflat-volumes = no\nresample-method = trivial\n"
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
        builder.environment()["HOME"] = configDirectory.absolutePath
        builder.environment()["PULSE_RUNTIME_PATH"] = File(configDirectory, "run").apply { mkdirs() }.absolutePath
        runtime.pulseLibraryDirectory?.let { builder.environment()["LD_LIBRARY_PATH"] = it.absolutePath }
        runtime.pulseModuleDirectory?.let { builder.environment()["PULSE_DLPATH"] = it.absolutePath }
        process = runCatching { builder.start() }.getOrNull()
        Thread.sleep(300)
        process?.isAlive == true
    }

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
