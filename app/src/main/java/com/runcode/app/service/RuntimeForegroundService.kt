package com.runcode.app.service

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
import com.runcode.app.R
import com.runcode.app.RuncodeApp
import com.runcode.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RuntimeForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "runcode_services_channel"
        const val NOTIFICATION_ID = 4040
        const val ACTION_START_FOREGROUND = "com.runcode.app.action.START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "com.runcode.app.action.STOP_FOREGROUND"
        const val ACTION_STOP_ALL = "com.runcode.app.action.STOP_ALL"
        const val EXTRA_RUNNING_COUNT = "extra_running_count"
        const val EXTRA_BRIDGE_ACTIVE = "extra_bridge_active"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start arrives via startForegroundService(), which requires startForeground()
        // within a few seconds — including the starts that only exist to shut us down again.
        val runningCount = intent?.getIntExtra(EXTRA_RUNNING_COUNT, 1) ?: 1
        val bridgeActive = intent?.getBooleanExtra(EXTRA_BRIDGE_ACTIVE, false) ?: false
        enterForeground(runningCount, bridgeActive)

        when (intent?.action) {
            ACTION_STOP_ALL -> serviceScope.launch {
                (application as? RuncodeApp)?.serviceSupervisor?.stopAll()
                withContext(Dispatchers.Main) { shutdown() }
            }

            ACTION_STOP_FOREGROUND -> shutdown()
        }

        return START_NOT_STICKY
    }

    private fun enterForeground(runningCount: Int, bridgeActive: Boolean) {
        val notification = buildNotification(runningCount, bridgeActive)
        try {
            // FOREGROUND_SERVICE_TYPE_SPECIAL_USE only exists from API 34; on older releases
            // the untyped overload picks up whatever the manifest declares.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when the app is in the background.
            // The runtimes keep going; we just cannot show the ongoing notification.
        }
    }

    private fun shutdown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(runningCount: Int, bridgeActive: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopAllIntent = Intent(this, RuntimeForegroundService::class.java).apply {
            action = ACTION_STOP_ALL
        }
        val stopAllPendingIntent = PendingIntent.getService(
            this,
            1,
            stopAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentTitle = when {
            runningCount == 1 -> "runcode: 1 service running"
            runningCount > 1 -> "runcode: $runningCount services running"
            bridgeActive -> "runcode: MCP bridge online"
            else -> "runcode: idle"
        }

        val contentText = if (bridgeActive) {
            "An MCP client can control this device while the bridge is online"
        } else {
            "Local development runtime and supervisor active"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_launcher_foreground, "Stop All", stopAllPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
