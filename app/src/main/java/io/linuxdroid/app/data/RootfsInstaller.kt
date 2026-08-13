package io.linuxdroid.app.data

import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

class RootfsInstaller(private val local: LocalRepository) {
    suspend fun install(
        definition: RootfsDefinition,
        architecture: String,
        archive: File,
        onProgress: (files: Long) -> Unit
    ): InstalledDistro = withContext(Dispatchers.IO) {
        require(architecture in setOf("arm64-v8a", "armeabi-v7a")) { "Unsupported device ABI." }
        val staging = File(local.distributionsDirectory(), ".staging-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Could not create installation staging directory." }
        try {
            extractTar(archive, staging, onProgress)
            bootstrapFilesystem(staging)
            val finalDirectory = File(local.distributionsDirectory(), "${definition.id}-${UUID.randomUUID().toString().take(8)}")
            check(staging.renameTo(finalDirectory)) { "Could not finalize rootfs installation." }
            local.addInstalled(definition, architecture, finalDirectory)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private suspend fun extractTar(archive: File, target: File, onProgress: (Long) -> Unit) {
        var files = 0L
        FileInputStream(archive).use { fileInput ->
            BufferedInputStream(fileInput).use { buffered ->
                CompressorStreamFactory(true).createCompressorInputStream(buffered).use { compressed ->
                    TarArchiveInputStream(compressed).use { tar ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val entry = tar.nextTarEntry ?: break
                            val relative = sanitizeEntryName(entry.name)
                            if (relative.isEmpty()) continue
                            val output = File(target, relative)
                            check(output.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                                "Unsafe rootfs archive path rejected: ${entry.name}"
                            }
                            when {
                                entry.isDirectory -> check(output.mkdirs() || output.isDirectory) { "Could not create $relative" }
                                entry.isSymbolicLink -> createSafeSymlink(target, output, entry)
                                entry.isLink -> throw SecurityException("Hard links are not accepted in rootfs archives.")
                                entry.isFile -> {
                                    output.parentFile?.let { parent ->
                                        check(parent.mkdirs() || parent.isDirectory) { "Could not create rootfs parent directory." }
                                        check(!isSymlink(parent)) { "Archive attempted to write through a symbolic link." }
                                    }
                                    FileOutputStream(output).use { destination -> tar.copyTo(destination) }
                                    if ((entry.mode and 0b001_001_001) != 0) runCatching { Os.chmod(output.absolutePath, entry.mode) }
                                }
                            }
                            files++
                            if (files % 100L == 0L) onProgress(files)
                        }
                    }
                }
            }
        }
        check(files > 0) { "Rootfs archive was empty." }
    }

    private fun sanitizeEntryName(name: String): String {
        val normalized = name.replace('\\', '/')
            .removePrefix("./")
            .trim('/')
        require(normalized.isNotBlank()) { "Blank rootfs archive entry." }
        require(!normalized.startsWith("../") && "/../" !in normalized && normalized != "..") {
            "Path traversal in rootfs archive."
        }
        require(!normalized.startsWith('/')) { "Absolute rootfs archive path." }
        return normalized
    }

    private fun createSafeSymlink(root: File, output: File, entry: TarArchiveEntry) {
        val link = entry.linkName
        require(link.isNotBlank()) { "Empty symbolic link in rootfs archive." }
        val rootPath = root.absolutePath
        val outputPath = output.absolutePath
        require(outputPath.startsWith(rootPath + File.separator)) { "Unsafe symbolic link path." }
        if (link.startsWith('/')) {
            // Absolute links are interpreted by the PRoot guest after installation.
            // They cannot escape the Android app sandbox during archive extraction.
        } else {
            val linkPath = outputPath.removePrefix(rootPath).trimStart(File.separatorChar)
            require(RootfsPathPolicy.isSafeRelativeSymlink(linkPath, link)) {
                "Relative symbolic link escapes the RootFS: $link"
            }
        }
        output.parentFile?.let { parent ->
            check(parent.mkdirs() || parent.isDirectory) { "Could not create symbolic-link parent directory." }
            check(!isSymlink(parent)) { "Archive attempted to create a symbolic link through a link." }
        }
        if (output.exists() || isSymlink(output)) output.delete()
        Os.symlink(link, output.absolutePath)
    }

    private fun isSymlink(file: File): Boolean = runCatching {
        Os.readlink(file.absolutePath)
        true
    }.getOrDefault(false)

    private fun bootstrapFilesystem(root: File) {
        listOf("dev", "proc", "sys", "tmp", "run", "root", "mnt", "sdcard").forEach {
            File(root, it).mkdirs()
        }
        File(root, "tmp").setWritable(true, false)
        File(root, "tmp").setExecutable(true, false)
    }
}
