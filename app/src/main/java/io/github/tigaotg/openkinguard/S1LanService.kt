package io.github.tigaotg.openkinguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
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
        @Volatile private var startupFailure: String? = null

        /** A click is a request, not proof that Android started the service. */
        fun requestStart(context: Context): String {
            startupFailure = null
            return runCatching {
                val intent = Intent(context, S1LanService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
                "正在开启持续监听，等待系统确认"
            }.getOrElse { failure ->
                startupFailure = "系统拒绝启动：${failure.javaClass.simpleName}"
                requireNotNull(startupFailure)
            }
        }

        fun requestStop(context: Context): String = runCatching {
            context.startService(Intent(context, S1LanService::class.java)
                .setAction(ACTION_STOP))
            "正在停止持续监听，等待系统确认"
        }.getOrElse { failure ->
            "停止失败：${failure.javaClass.simpleName}"
        }

        fun lastStartupFailure(): String? = startupFailure
    }

    private var scheduler: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android requires an ongoing visible notification before opening the LAN sockets.
        startupFailure = null
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            "持续通信监听", NotificationManager.IMPORTANCE_LOW).apply {
            description = "由用户主动开启的已绑定设备通信；不保证系统始终在线"
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
            .setContentText("已开启设备通信监听；系统省电仍可能中断")
            .setContentIntent(activityIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止监听", stopIntent).build())
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (error: Exception) {
            startupFailure = "前台通知或服务启动失败：${error.javaClass.simpleName}"
            Log.w("OpenKinGuard", "Cannot start persistent notification", error)
            stopSelf()
            return START_NOT_STICKY
        }

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
                startupFailure = "持续监听初始化失败：${error.javaClass.simpleName}"
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
