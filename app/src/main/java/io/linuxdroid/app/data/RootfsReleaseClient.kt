package io.linuxdroid.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Discovers LinuxDroid RootFS images directly from a public GitHub Release.
 *
 * A RootFS asset must use this format:
 * linuxdroid-rootfs__<id>__<version>__<abi>.tar.xz
 *
 * Each image must have a sibling `<asset>.sha256` asset containing a standard
 * SHA-256 checksum line. The release itself is the source of truth; no JSON
 * catalog is fetched, stored, or interpreted.
 */
class RootfsReleaseClient {
    companion object {
        const val OWNER = "s-n-t09"
        const val REPOSITORY = "LinuxDroid"
        const val ROOTFS_RELEASE_TAG = "rootfs-pack-2"
        private const val API_VERSION = "2022-11-28"
        private val assetPattern = Regex(
            "^linuxdroid-rootfs__([a-z0-9][a-z0-9-]{0,62})__([a-z0-9][a-z0-9._-]{0,63})__(arm64-v8a|armeabi-v7a)\\.tar\\.xz$"
        )
    }

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchDistributions(): List<RootfsDefinition> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$OWNER/$REPOSITORY/releases/tags/$ROOTFS_RELEASE_TAG")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("User-Agent", "LinuxDroid-RootFS")
            .build()
        val release = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "LinuxDroid RootFS release is unavailable (HTTP ${response.code})."
            }
            json.decodeFromString(GitHubRelease.serializer(), response.body?.string().orEmpty())
        }
        require(release.tagName == ROOTFS_RELEASE_TAG) { "Unexpected RootFS release tag." }
        require(!release.draft && !release.prerelease) { "RootFS release is not published." }

        val checksumAssets = release.assets.associateBy { it.name }
        val parsed = release.assets.mapNotNull { asset ->
            val match = assetPattern.matchEntire(asset.name) ?: return@mapNotNull null
            val (_, id, version, abi) = match.groupValues
            ParsedImage(id, version, abi, asset)
        }
        require(parsed.isNotEmpty()) { "No supported LinuxDroid RootFS assets were found in the release." }

        val artifacts = coroutineScope {
            parsed.map { image ->
                async {
                    val checksumAsset = checksumAssets["${image.asset.name}.sha256"]
                        ?: error("Missing SHA-256 asset for ${image.asset.name}.")
                    val expected = image.asset.digest?.removePrefix("sha256:")
                        ?.lowercase()
                        ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                        ?: fetchChecksum(checksumAsset.browserDownloadUrl, image.asset.name)
                    image to RootfsArtifact(
                        url = image.asset.browserDownloadUrl,
                        sha256 = expected,
                        sizeBytes = image.asset.size,
                        format = "tar.xz"
                    )
                }
            }.awaitAll()
        }

        artifacts.groupBy { it.first.id }.map { (id, entries) ->
            val first = entries.first().first
            RootfsDefinition(
                id = id,
                title = descriptorFor(id).title,
                version = first.version,
                description = descriptorFor(id).description,
                architectures = entries.associate { it.first.abi to it.second },
                defaultUser = "root",
                homepage = descriptorFor(id).homepage
            )
        }.sortedBy { it.title.lowercase() }
    }

    private fun fetchChecksum(url: String, expectedName: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "LinuxDroid-RootFS").build()
        val text = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Could not fetch checksum for $expectedName (HTTP ${response.code})." }
            response.body?.string().orEmpty()
        }
        val line = text.lineSequence().firstOrNull { it.contains(expectedName) } ?: text.lineSequence().firstOrNull()
        val hash = line?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase().orEmpty()
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid SHA-256 data for $expectedName." }
        return hash
    }

    private fun descriptorFor(id: String): DistroDescriptor = descriptors[id] ?: DistroDescriptor(
        title = id.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) },
        description = "LinuxDroid release-backed RootFS image.",
        homepage = null
    )

    private data class ParsedImage(
        val id: String,
        val version: String,
        val abi: String,
        val asset: GitHubReleaseAsset
    )

    private data class DistroDescriptor(val title: String, val description: String, val homepage: String?)

    private val descriptors = mapOf(
        "alpine" to DistroDescriptor("Alpine Linux", "Small, security-focused musl and BusyBox distribution configured for LinuxDroid.", "https://alpinelinux.org/"),
        "archlinux" to DistroDescriptor("Arch Linux", "Rolling Arch Linux RootFS configured for LinuxDroid.", "https://archlinux.org/"),
        "debian-trixie" to DistroDescriptor("Debian Trixie", "Debian Trixie stable RootFS configured for LinuxDroid.", "https://www.debian.org/"),
        "fedora" to DistroDescriptor("Fedora Linux", "Fedora RootFS configured for LinuxDroid.", "https://fedoraproject.org/"),
        "ubuntu" to DistroDescriptor("Ubuntu", "Ubuntu RootFS configured for LinuxDroid.", "https://ubuntu.com/")
    )
}

@Serializable
private data class GitHubRelease(
    @kotlinx.serialization.SerialName("tag_name") val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubReleaseAsset> = emptyList()
)

@Serializable
private data class GitHubReleaseAsset(
    val name: String,
    @kotlinx.serialization.SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0L,
    val digest: String? = null
)
