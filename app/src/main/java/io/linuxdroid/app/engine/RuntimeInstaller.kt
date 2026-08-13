package io.linuxdroid.app.engine

import android.content.Context
import android.os.Build
import io.linuxdroid.app.data.LocalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class RuntimeInstaller(context: Context, private val local: LocalRepository) {
    private val appContext = context.applicationContext

    data class RuntimePaths(
        val abi: String,
        val proot: File,
        val pulseBinary: File?,
        val pulseLibraryDirectory: File?,
        val pulseModuleDirectory: File?
    )

    fun supportedAbi(): String {
        return Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
            when (abi) {
                "arm64-v8a" -> "arm64-v8a"
                "armeabi-v7a" -> "armeabi-v7a"
                else -> null
            }
        } ?: error("LinuxDroid currently supports only arm64-v8a and armeabi-v7a devices.")
    }

    suspend fun ensureInstalled(): RuntimePaths = withContext(Dispatchers.IO) {
        val abi = supportedAbi()
        val target = File(local.runtimeDirectory(), abi).apply { mkdirs() }
        val marker = File(target, ".runtime-v1")
        if (!marker.exists()) {
            copyAssetTree("runtime/$abi", target)
            marker.writeText("LinuxDroid runtime v1\n")
        }
        val packagedProot = File(appContext.applicationInfo.nativeLibraryDir, "libproot.so")
        check(packagedProot.isFile) {
            "Executable PRoot was not extracted by Android. Reinstall the current LinuxDroid APK so libproot.so is installed in the native library directory."
        }
        check(packagedProot.canExecute()) {
            "Android did not expose the packaged PRoot binary as executable: ${packagedProot.absolutePath}"
        }
        val proot = packagedProot
        val pulse = File(target, "pulse/bin/pulseaudio").takeIf { it.isFile }?.also { it.setExecutable(true, true) }
        RuntimePaths(
            abi = abi,
            proot = proot,
            pulseBinary = pulse,
            pulseLibraryDirectory = File(target, "pulse/lib").takeIf { it.isDirectory },
            pulseModuleDirectory = File(target, "pulse/lib").takeIf { it.isDirectory }
                ?.walkTopDown()
                ?.firstOrNull { candidate -> candidate.isDirectory && candidate.name == "modules" && candidate.parentFile?.name?.startsWith("pulse-") == true }
                ?: File(target, "pulse/modules").takeIf { it.isDirectory }
        )
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input -> destination.outputStream().use(input::copyTo) }
            return
        }
        destination.mkdirs()
        children.forEach { child -> copyAssetTree("$assetPath/$child", File(destination, child)) }
    }
}
