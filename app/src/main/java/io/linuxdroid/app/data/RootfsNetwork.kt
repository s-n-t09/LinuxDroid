package io.linuxdroid.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Downloads a release asset with resume support and enforces its SHA-256 checksum. */
class RootfsNetwork {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun downloadVerified(
        artifact: RootfsArtifact,
        output: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        require(artifact.url.startsWith("https://")) { "RootFS downloads must use HTTPS." }
        require(artifact.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "RootFS SHA-256 metadata is invalid." }
        output.parentFile?.mkdirs()
        val partial = File(output.parentFile, "${output.name}.part")
        val offset = partial.takeIf { it.exists() }?.length() ?: 0L
        val requestBuilder = Request.Builder().url(artifact.url).header("User-Agent", "LinuxDroid-RootFS")
        if (offset > 0L) requestBuilder.header("Range", "bytes=$offset-")

        client.newCall(requestBuilder.build()).execute().use { response ->
            check(response.isSuccessful) { "RootFS request failed: HTTP ${response.code}" }
            val append = offset > 0L && response.code == 206
            if (!append && partial.exists()) partial.delete()
            val initial = if (append) offset else 0L
            val advertisedLength = response.body?.contentLength() ?: -1L
            val total = when {
                artifact.sizeBytes > 0 -> artifact.sizeBytes
                advertisedLength >= 0 -> initial + advertisedLength
                else -> -1L
            }
            response.body?.byteStream()?.use { input ->
                FileOutputStream(partial, append).use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = initial
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                        copied += count
                        onProgress(copied, total)
                    }
                    target.fd.sync()
                }
            } ?: error("Empty RootFS response body.")
        }

        val calculated = sha256(partial)
        check(calculated.equals(artifact.sha256, ignoreCase = true)) {
            partial.delete()
            "RootFS integrity verification failed (SHA-256 mismatch)."
        }
        if (output.exists()) output.delete()
        check(partial.renameTo(output)) { "Could not finalize the verified RootFS archive." }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
