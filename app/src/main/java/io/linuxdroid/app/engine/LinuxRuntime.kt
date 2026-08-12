package io.linuxdroid.app.engine

import android.content.Context

object LinuxRuntime {
    @Volatile
    private var controller: LinuxSessionController? = null

    fun controller(context: Context): LinuxSessionController {
        return controller ?: synchronized(this) {
            controller ?: LinuxSessionController(context.applicationContext).also { controller = it }
        }
    }
}
