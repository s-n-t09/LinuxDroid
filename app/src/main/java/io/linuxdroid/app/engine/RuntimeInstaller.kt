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
        val prootLoader: File,
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
        val marker = File(target, ".runtime-v2")
        if (!marker.exists()) {
            // Overlay the current assets even when an earlier LinuxDroid runtime
            // is present, so existing installations receive corrected PulseAudio modules.
            copyAssetTree("runtime/$abi", target)
            marker.writeText("LinuxDroid runtime v2\n")
        }
        val packagedProot = File(appContext.applicationInfo.nativeLibraryDir, "libproot.so")
        check(packagedProot.isFile) {
            "Executable PRoot was not extracted by Android. Reinstall the current LinuxDroid APK so libproot.so is installed in the native library directory."
        }
        check(packagedProot.canExecute()) {
            "Android did not expose the packaged PRoot binary as executable: ${packagedProot.absolutePath}"
        }
        val proot = packagedProot
        val packagedLoader = File(appContext.applicationInfo.nativeLibraryDir, "libproot_loader.so")
        check(packagedLoader.isFile && packagedLoader.canExecute()) {
            "The matching PRoot loader was not extracted by Android. Reinstall the current LinuxDroid APK so libproot_loader.so is installed in the native library directory."
        }
        val pulse = File(target, "pulse/bin/pulseaudio").takeIf { it.isFile }?.also { it.setExecutable(true, true) }
        RuntimePaths(
            abi = abi,
            proot = proot,
            prootLoader = packagedLoader,
            pulseBinary = pulse,
            pulseLibraryDirectory = File(target, "pulse/lib").takeIf { it.isDirectory },
            // Termux packages modules at pulse/lib/pulseaudio/modules. The old
            // predicate looked for a parent beginning with "pulse-", so no
            // module directory was ever passed to the daemon on Android.
            pulseModuleDirectory = File(target, "pulse/lib/pulseaudio/modules").takeIf { it.isDirectory }
                ?: File(target, "pulse/lib").takeIf { it.isDirectory }
                    ?.walkTopDown()
                    ?.firstOrNull { candidate -> candidate.isDirectory && candidate.name == "modules" }
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
