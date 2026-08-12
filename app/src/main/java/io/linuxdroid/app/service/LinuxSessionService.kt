package io.linuxdroid.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.linuxdroid.app.MainActivity
import io.linuxdroid.app.R
import io.linuxdroid.app.data.LocalRepository
import io.linuxdroid.app.engine.LinuxRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LinuxSessionService : Service() {
    companion object {
        const val ACTION_START = "io.linuxdroid.app.START_SESSION"
        const val ACTION_STOP = "io.linuxdroid.app.STOP_SESSION"
        const val EXTRA_INSTALL_ID = "install_id"
        private const val CHANNEL_ID = "linux_session"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, installId: String) {
            val intent = Intent(context, LinuxSessionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_INSTALL_ID, installId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LinuxSessionService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val local by lazy { LocalRepository(this) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        LinuxRuntime.controller(this).onSessionEnded = { updateNotification("Session ended") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                promote("Starting Linux session…")
                val installId = intent.getStringExtra(EXTRA_INSTALL_ID) ?: return START_NOT_STICKY
                scope.launch {
                    runCatching {
                        val distro = local.listInstalled().firstOrNull { it.installId == installId }
                            ?: error("Requested distribution is no longer installed.")
                        val controller = LinuxRuntime.controller(this@LinuxSessionService)
                        if (controller.session == null) controller.start(distro)
                        updateNotification("${distro.title} is running")
                    }.onFailure { failure ->
                        updateNotification("Session failed: ${failure.message ?: "unknown error"}")
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> {
                scope.launch {
                    LinuxRuntime.controller(this@LinuxSessionService).stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun promote(message: String) {
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LinuxSessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_linuxdroid)
            .setContentTitle("LinuxDroid")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_stat_linuxdroid, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Linux sessions", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Visible while LinuxDroid is running a Linux session."
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
