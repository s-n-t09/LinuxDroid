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
        val marker = File(target, ".runtime-v3")
        if (!marker.exists()) {
            // Overlay the current assets even when an earlier LinuxDroid runtime
            // is present, so existing installations receive corrected PulseAudio modules.
            copyAssetTree("runtime/$abi", target)
            marker.writeText("LinuxDroid runtime v3\n")
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
        // Arbitrary files under filesDir are noexec on current Android builds.
        // The PulseAudio daemon, libraries, and modules are packaged in jniLibs
        // and extracted by Android beside PRoot into nativeLibraryDir instead.
        val pulse = File(appContext.applicationInfo.nativeLibraryDir, "liblinuxdroid_pulseaudio.so")
            .takeIf { it.isFile && it.canExecute() }
        val nativeLibraryDirectory = File(appContext.applicationInfo.nativeLibraryDir)
        RuntimePaths(
            abi = abi,
            proot = proot,
            prootLoader = packagedLoader,
            pulseBinary = pulse,
            pulseLibraryDirectory = nativeLibraryDirectory.takeIf { it.isDirectory },
            pulseModuleDirectory = nativeLibraryDirectory.takeIf { directory ->
                directory.listFiles().orEmpty().any { child ->
                    child.name.startsWith("module-") && child.name.endsWith(".so")
                }
            }
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
