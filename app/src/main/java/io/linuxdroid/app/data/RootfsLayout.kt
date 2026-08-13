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
                directory.name !in bootstrapDirectories || !directory.isDirectory || isSymlink(directory)
            }) return
        // Earlier installs may have created mount points beside the archive wrapper.
        // They cannot contain guest data because the guest filesystem is still inside
        // the verified wrapper, so remove them before promoting the wrapper safely.
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

    private val bootstrapDirectories = setOf("dev", "proc", "sys", "tmp", "run", "root", "mnt", "sdcard")

    private fun isSymlink(file: File): Boolean = runCatching {
        file.canonicalFile != file.absoluteFile
    }.getOrDefault(false)
}
