package io.linuxdroid.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.os.Environment
import io.linuxdroid.app.data.AppSettings
import io.linuxdroid.app.data.InstalledDistro
import java.io.File
import java.nio.file.Files

data class ProotLaunch(
    val shellPath: String,
    val workingDirectory: String,
    val arguments: Array<String>,
    val environment: Array<String>
)

class ProotCommandFactory(private val context: Context) {
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
        // PRoot performs a safe f2fs kernel probe before launching the guest.
        // Its default temporary location may be inaccessible on Android app storage.
        val prootTemporaryDirectory = File(root, ".linuxdroid-proot-tmp").apply { mkdirs() }
        val command = mutableListOf(
            shellQuote(runtime.proot.absolutePath),
            // Do not enable link2symlink. The RootFS lives in the app-private
            // directory owned by this process, so dpkg can use real hard links
            // for its statoverride backup transaction.
            "-0",
            "-r", shellQuote(root.absolutePath),
            // Do not bind /bin, /lib, or /usr over the guest root. Modern images
            // commonly make these paths symlinks into /usr; rebinding them can
            // bypass PRoot's guest-path resolution and leave the login shell inert.
            // PROOT_LOADER below handles the first guest ELF interpreter safely.
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
        if (distro.id == "debian-trixie") {
            prepareDebianResolver(root)
            prepareDebianDpkgState(root)
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
            "PROOT_TMP_DIR=${prootTemporaryDirectory.absolutePath}",
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

    /**
     * Debian images can carry an absolute /etc/resolv.conf symlink to an absent
     * systemd-resolved stub. Replace that link with a normal guest file on every
     * launch and use Android's active DNS servers, not hard-coded public DNS.
     */
    private fun prepareDebianResolver(root: File) {
        val resolver = File(root, "etc/resolv.conf")
        resolver.parentFile?.mkdirs()
        // File.delete removes a symlink itself, rather than its target.
        resolver.delete()
        val servers = activeDnsServers().ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        resolver.writeText(buildString {
            servers.distinct().forEach { append("nameserver ").append(it).append('\n') }
            append("options timeout:2 attempts:3\n")
        })
        resolver.setReadable(true, false)
    }

    /**
     * Some imported Debian states have a directory or stale symbolic link at
     * statoverride(-old). dpkg-statoverride must replace regular files there;
     * repairing only invalid entries preserves a healthy package database while
     * preventing dbus and PulseAudio package setup from failing under PRoot.
     */
    private fun prepareDebianDpkgState(root: File) {
        val admin = File(root, "var/lib/dpkg").apply { mkdirs() }
        val state = File(admin, "statoverride")
        val backup = File(admin, "statoverride-old")
        if (state.isDirectory || Files.isSymbolicLink(state.toPath())) state.deleteRecursively()
        if (!state.exists()) state.writeText("")
        state.setReadable(true, false)
        state.setWritable(true, false)
        if (backup.isDirectory || Files.isSymbolicLink(backup.toPath())) backup.deleteRecursively()
        // dpkg creates a new backup before every update. A stale unusable backup
        // is never authoritative and can block that atomic replacement.
        if (backup.exists()) backup.delete()
    }

    private fun activeDnsServers(): List<String> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return manager.activeNetwork?.let(manager::getLinkProperties)
            ?.dnsServers
            ?.mapNotNull { it.hostAddress?.substringBefore('%') }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
