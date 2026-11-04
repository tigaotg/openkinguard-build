package io.github.tigaotg.openkinguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Explicitly enabled connected-device foreground service. Not guaranteed 24/7 by Android. */
class S1LanService : Service() {
    companion object {
        const val ACTION_START = "io.github.tigaotg.openkinguard.START_LAN_TEST"
        const val ACTION_STOP = "io.github.tigaotg.openkinguard.STOP_LAN_TEST"
        private const val CHANNEL_ID = "okg_s2_connected_device_v1"
        private const val NOTIFICATION_ID = 205
    }

    private var scheduler: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android requires an ongoing visible notification before opening the LAN sockets.
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            "持续局域网监听（演练）", NotificationManager.IMPORTANCE_LOW).apply {
            description = "经用户开启的局域网后台互助演练；不保证真实紧急救援"
        })
        val activityIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1,
            Intent(this, S1LanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("OpenKinGuard · 持续监听中")
            .setContentText("正在监听已绑定局域网设备（演练，非真实救援）")
            .setContentIntent(activityIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止监听", stopIntent).build())
            .build()
        startForeground(NOTIFICATION_ID, notification)

        if (scheduler == null) {
            try {
                S1Runtime.attachService(applicationContext)
                val executor = Executors.newSingleThreadScheduledExecutor { job ->
                    Thread(job, "okg-s2-background-heartbeat").apply { isDaemon = true }
                }
                scheduler = executor
                // Give fresh mDNS discovery/PING a chance before issuing an offline warning.
                val startedAt = android.os.SystemClock.elapsedRealtime()
                executor.scheduleWithFixedDelay({
                    try {
                        val manager = S1Runtime.manager(applicationContext)
                        manager.probeAsync()
                        manager.expireOldIncoming()
                        manager.deliverPending()
                        if (android.os.SystemClock.elapsedRealtime() - startedAt >= 60_000L)
                            manager.checkLiveness()
                        GuardWidgetPublisher.publishFromManager(applicationContext, manager)
                    } catch (error: Exception) {
                        Log.w("OpenKinGuard", "Background LAN tick failed", error)
                    }
                }, 0L, 60L, TimeUnit.SECONDS)
            } catch (error: Exception) {
                Log.w("OpenKinGuard", "Cannot start optional LAN listener", error)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scheduler?.shutdownNow()
        scheduler = null
        S1Runtime.detachService()
        if (!S1Runtime.isUiVisible()) GuardWidgetPublisher.markInactive(applicationContext)
        super.onDestroy()
    }
}
