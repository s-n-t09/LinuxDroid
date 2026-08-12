package io.linuxdroid.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class LocalRepository(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val stateDirectory = File(appContext.filesDir, "state").apply { mkdirs() }
    private val installsFile = File(stateDirectory, "installed-distributions.json")
    private val settingsFile = File(stateDirectory, "settings.json")

    fun distributionsDirectory(): File = File(appContext.filesDir, "distributions").apply { mkdirs() }
    fun downloadsDirectory(): File = File(appContext.cacheDir, "downloads").apply { mkdirs() }
    fun runtimeDirectory(): File = File(appContext.filesDir, "runtime").apply { mkdirs() }
    fun logsDirectory(): File = File(appContext.filesDir, "logs").apply { mkdirs() }

    suspend fun listInstalled(): List<InstalledDistro> = withContext(Dispatchers.IO) {
        if (!installsFile.exists()) return@withContext emptyList()
        runCatching { json.decodeFromString(ListSerializer(InstalledDistro.serializer()), installsFile.readText()) }
            .getOrDefault(emptyList())
    }

    suspend fun saveInstalled(items: List<InstalledDistro>) = withContext(Dispatchers.IO) {
        atomicWrite(installsFile, json.encodeToString(ListSerializer(InstalledDistro.serializer()), items))
    }

    suspend fun addInstalled(
        definition: RootfsDefinition,
        architecture: String,
        rootfsDirectory: File
    ): InstalledDistro = withContext(Dispatchers.IO) {
        val record = InstalledDistro(
            id = definition.id,
            title = definition.title,
            version = definition.version,
            installId = "${definition.id}-${UUID.randomUUID().toString().take(8)}",
            architecture = architecture,
            rootfsDirectory = rootfsDirectory.absolutePath,
            installedAtEpochMs = System.currentTimeMillis()
        )
        val current = listInstalled()
        saveInstalled(current + record)
        record
    }

    suspend fun updateInstalled(updated: InstalledDistro) = withContext(Dispatchers.IO) {
        saveInstalled(listInstalled().map { if (it.installId == updated.installId) updated else it })
    }

    suspend fun removeInstalled(installId: String) = withContext(Dispatchers.IO) {
        saveInstalled(listInstalled().filterNot { it.installId == installId })
    }

    suspend fun settings(): AppSettings = withContext(Dispatchers.IO) {
        if (!settingsFile.exists()) return@withContext AppSettings()
        runCatching { json.decodeFromString(AppSettings.serializer(), settingsFile.readText()) }.getOrDefault(AppSettings())
    }

    suspend fun saveSettings(settings: AppSettings) = withContext(Dispatchers.IO) {
        atomicWrite(settingsFile, json.encodeToString(AppSettings.serializer(), settings))
    }

    private fun atomicWrite(target: File, content: String) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(target)) {
            target.writeText(content)
            temporary.delete()
        }
    }
}
