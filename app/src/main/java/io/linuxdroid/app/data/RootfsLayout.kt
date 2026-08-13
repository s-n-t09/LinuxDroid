package io.linuxdroid.app.data

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/** Normalizes PRoot Distro archive layouts for an Android-hosted guest filesystem. */
internal object RootfsLayout {
    /** Promotes a single archive wrapper such as `alpine-aarch64/` when present. */
    fun normalizeTopLevelDirectory(root: File) {
        val children = root.listFiles()?.toList().orEmpty()
        val wrappers = children.filter { candidate ->
            candidate.isDirectory && !isSymlink(candidate) &&
                File(candidate, "bin").isDirectory && File(candidate, "etc").isDirectory
        }
        if (wrappers.size != 1) return

        val wrapper = wrappers.single()
        val auxiliary = children.filterNot { it == wrapper }
        if (auxiliary.any { directory ->
                directory.name !in bootstrapDirectories || !directory.isDirectory || isSymlink(directory)
            }) return
        auxiliary.forEach { directory ->
            check(directory.deleteRecursively()) { "Could not remove obsolete RootFS bootstrap directory." }
        }

        val nested = wrapper.listFiles()?.toList().orEmpty()
        check(nested.isNotEmpty()) { "RootFS wrapper directory is empty." }
        nested.forEach { child ->
            val destination = File(root, child.name)
            check(!destination.exists() && !isSymlink(destination)) {
                "RootFS wrapper conflicts with existing path: ${child.name}"
            }
            check(child.renameTo(destination)) { "Could not normalize RootFS path: ${child.name}" }
        }
        check(wrapper.delete()) { "Could not remove RootFS wrapper directory." }
    }

    /**
     * An archive's absolute guest links are interpreted against Android's host root
     * before PRoot starts. Rebase links whose targets exist inside [root], leaving
     * virtual links such as `/proc/mounts` absolute for PRoot to provide.
     */
    fun rebaseGuestAbsoluteSymlinks(root: File) {
        root.walkTopDown().forEach { link ->
            if (!isSymlink(link)) return@forEach
            val target = runCatching { Files.readSymbolicLink(link.toPath()).toString() }.getOrNull() ?: return@forEach
            if (!target.startsWith('/')) return@forEach

            val guestTarget = File(root, target.removePrefix("/"))
            if (!guestTarget.exists() && !isSymlink(guestTarget)) return@forEach
            val parent = link.parentFile ?: return@forEach
            val relative = parent.toPath().relativize(guestTarget.toPath()).toString().replace(File.separatorChar, '/')
            check(relative.isNotBlank()) { "Invalid absolute guest link: $target" }
            Files.delete(link.toPath())
            Files.createSymbolicLink(link.toPath(), Paths.get(relative))
        }
    }

    fun requireGuestShell(root: File) {
        check(File(root, "bin/sh").isFile) {
            "RootFS does not provide an executable /bin/sh after layout normalization."
        }
    }

    private val bootstrapDirectories = setOf("dev", "proc", "sys", "tmp", "run", "root", "mnt", "sdcard")

    private fun isSymlink(file: File): Boolean = Files.isSymbolicLink(file.toPath())
}
