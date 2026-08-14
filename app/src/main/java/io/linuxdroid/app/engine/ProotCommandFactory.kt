package io.linuxdroid.app.engine

import android.os.Environment
import io.linuxdroid.app.data.InstalledDistro
import io.linuxdroid.app.data.AppSettings
import java.io.File

data class ProotLaunch(
    val shellPath: String,
    val workingDirectory: String,
    val arguments: Array<String>,
    val environment: Array<String>
)

class ProotCommandFactory {
    fun createInteractiveLaunch(
        distro: InstalledDistro,
        runtime: RuntimeInstaller.RuntimePaths,
        settings: AppSettings
    ): ProotLaunch {
        val root = File(distro.rootfsDirectory)
        require(root.isDirectory) { "Installed rootfs is unavailable." }
        val guestShell = if (
            (distro.id.startsWith("debian") || distro.id.startsWith("ubuntu")) && File(root, "bin/bash").isFile
        ) "/bin/bash" else "/bin/sh"
        val command = mutableListOf(
            shellQuote(runtime.proot.absolutePath),
            "--link2symlink",
            "-0",
            "-r", shellQuote(root.absolutePath),
            // Bind the guest executable and loader paths explicitly. This avoids
            // Android-host path resolution for ELF interpreters such as Alpine's
            // /lib/ld-musl-aarch64.so.1 during the first execve.
            "-b", "${shellQuote(root.absolutePath)}/bin:/bin",
            "-b", "${shellQuote(root.absolutePath)}/lib:/lib",
            "-b", "${shellQuote(root.absolutePath)}/usr:/usr",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${shellQuote(root.absolutePath)}/tmp:/tmp"
        )
        if (settings.enableAllFilesBinding && Environment.isExternalStorageManager()) {
            val shared = Environment.getExternalStorageDirectory()
            if (shared.canRead()) {
                File(root, "storage/emulated/0").mkdirs()
                File(root, "sdcard").mkdirs()
                command.addAll(listOf(
                    "-b", "${shellQuote(shared.absolutePath)}:/storage/emulated/0",
                    "-b", "${shellQuote(shared.absolutePath)}:/sdcard"
                ))
            }
        }
        command.addAll(listOf("-w", "/root", guestShell, "-l"))
        val environment = mutableListOf(
            "HOME=/root",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PS1=\\u@\\h:\\w\\# ",
            "LINUXDROID_MOTD_ENABLED=${if (settings.showMotdOnStart) "1" else "0"}",
            "PROOT_LOADER=${runtime.prootLoader.absolutePath}"
        )
        if (settings.pulseAudioEnabled && runtime.pulseBinary != null) {
            // All guest clients communicate with LinuxDroid's private loopback
            // PulseAudio daemon. ALSA clients use /etc/asound.conf from the RootFS.
            environment += "PULSE_SERVER=tcp:127.0.0.1:4713"
            environment += "PULSE_COOKIE=/tmp/linuxdroid-pulse.cookie"
            environment += "PULSE_CLIENTCONFIG=/etc/pulse/client.conf"
            environment += "PULSE_LATENCY_MSEC=60"
            environment += "ALSA_CONFIG_PATH=/etc/asound.conf"
        }
        return ProotLaunch(
            shellPath = "/system/bin/sh",
            workingDirectory = root.absolutePath,
            arguments = arrayOf("sh", "-lc", "exec ${command.joinToString(" ")}"),
            environment = environment.toTypedArray()
        )
    }

    fun createGuestCommand(
        distro: InstalledDistro,
        runtime: RuntimeInstaller.RuntimePaths,
        settings: AppSettings,
        guestCommand: String
    ): ProotLaunch {
        val base = createInteractiveLaunch(distro, runtime, settings)
        val adjusted = base.arguments.copyOf()
        adjusted[2] = adjusted[2].substringBeforeLast(" -w ") + " -w /root /bin/sh -lc ${shellQuote(guestCommand)}"
        return base.copy(arguments = adjusted)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
