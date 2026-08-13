package io.linuxdroid.app.data

import java.io.File

/** Normalizes RootFS archives that wrap the actual filesystem in one top-level directory. */
internal object RootfsLayout {
    /**
     * PRoot Distro release archives commonly contain one wrapper such as
     * `alpine-aarch64/`. LinuxDroid expects `/bin`, `/etc`, and `/usr` directly
     * below [root], so promote the wrapper's children only when it is unambiguous.
     */
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
                directory.name !in bootstrapDirectories || !directory.isDirectory || isSymlink(directory) || directory.listFiles()?.isEmpty() != true
            }) return
        // A failed pre-v0.1.5 install may have these empty host mount points next
        // to the archive wrapper. Remove them only after the wrapper is proven safe.
        auxiliary.forEach { directory -> check(directory.delete()) { "Could not remove empty RootFS bootstrap directory." } }

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

    private val bootstrapDirectories = setOf("dev", "proc", "sys", "tmp", "run", "root", "mnt", "sdcard")

    private fun isSymlink(file: File): Boolean = runCatching {
        file.canonicalFile != file.absoluteFile
    }.getOrDefault(false)
}
