package io.linuxdroid.app.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsLayoutTest {
    @Test
    fun promotesSingleRootfsWrapperContainingBin() {
        val root = Files.createTempDirectory("linuxdroid-rootfs-layout").toFile()
        try {
            val wrapper = File(root, "alpine-aarch64")
            check(File(wrapper, "bin").mkdirs())
            check(File(wrapper, "etc").mkdirs())
            File(wrapper, "bin/sh").writeText("shell")

            RootfsLayout.normalizeTopLevelDirectory(root)

            assertTrue(File(root, "bin/sh").isFile)
            assertTrue(File(root, "etc").isDirectory)
            assertFalse(wrapper.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun promotesPreviouslyInstalledWrapperBesideEmptyBootstrapDirectories() {
        val root = Files.createTempDirectory("linuxdroid-rootfs-legacy").toFile()
        try {
            val wrapper = File(root, "alpine-aarch64")
            check(File(wrapper, "bin").mkdirs())
            check(File(wrapper, "etc").mkdirs())
            File(wrapper, "bin/sh").writeText("shell")
            listOf("dev", "proc", "sys", "tmp", "run", "root", "mnt", "sdcard").forEach {
                check(File(root, it).mkdirs())
            }
            File(root, "tmp/legacy-marker").writeText("obsolete bootstrap content")

            RootfsLayout.normalizeTopLevelDirectory(root)

            assertTrue(File(root, "bin/sh").isFile)
            assertFalse(wrapper.exists())
            assertFalse(File(root, "tmp").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rebasesAlpineAbsoluteShellLinkAfterPromotingWrapper() {
        val root = Files.createTempDirectory("linuxdroid-rootfs-alpine-link").toFile()
        try {
            val wrapper = File(root, "alpine-aarch64")
            check(File(wrapper, "bin").mkdirs())
            check(File(wrapper, "etc").mkdirs())
            File(wrapper, "bin/busybox").writeText("busybox")
            Files.createSymbolicLink(File(wrapper, "bin/sh").toPath(), java.nio.file.Paths.get("/bin/busybox"))

            RootfsLayout.normalizeTopLevelDirectory(root)
            RootfsLayout.rebaseGuestAbsoluteSymlinks(root)
            RootfsLayout.requireGuestShell(root)

            assertTrue(File(root, "bin/sh").isFile)
            assertTrue(Files.readSymbolicLink(File(root, "bin/sh").toPath()).toString().contains("busybox"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun leavesAlreadyNormalizedRootfsUntouched() {
        val root = Files.createTempDirectory("linuxdroid-rootfs-normal").toFile()
        try {
            check(File(root, "bin").mkdirs())
            check(File(root, "etc").mkdirs())

            RootfsLayout.normalizeTopLevelDirectory(root)

            assertTrue(File(root, "bin").isDirectory)
            assertTrue(File(root, "etc").isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }
}
