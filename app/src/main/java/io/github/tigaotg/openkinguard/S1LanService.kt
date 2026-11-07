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
import android.os.SystemClock
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
        @Volatile private var attachedAtElapsed = 0L
        @Volatile private var lastTickElapsed = 0L
        @Volatile private var lastStartRequestElapsed = 0L
        @Volatile private var lastAutomaticAttemptElapsed = 0L

        data class Probe(val healthy: Boolean, val starting: Boolean, val failure: String?)

        /** A fresh scheduler tick is stronger evidence than a stored switch or service request. */
        fun runtimeProbe(): Probe {
            val now = SystemClock.elapsedRealtime()
            val attached = S1Runtime.isBackgroundListening()
            val fresh = attached && lastTickElapsed > 0L &&
                now - lastTickElapsed in 0L..150_000L
            val starting = !fresh && (
                (attached && attachedAtElapsed > 0L && now - attachedAtElapsed in 0L..10_000L) ||
                (lastStartRequestElapsed > 0L &&
                    now - lastStartRequestElapsed in 0L..7_000L)) && startupFailure == null
            val stalled = if (attached && !fresh && !starting)
                "后台监听服务没有新的执行心跳" else null
            return Probe(fresh, starting, startupFailure ?: stalled)
        }

        /** A click is a request, not proof that Android started the service. */
        fun requestStart(context: Context): String {
            // Only a deliberate local click may change an opt-out into consent.
            BackgroundConsentStore.set(context, true)
            return startRequested(context)
        }

        /** Only for previously consenting users; never overrides their explicit OFF choice. */
        fun requestAutoRecovery(context: Context): String {
            if (BackgroundConsentStore.get(context) != BackgroundConsent.ENABLED)
                return "没有后台监听授权，不会自动启动"
            if (runtimeProbe().healthy) return "后台监听已正常运行"
            val now = SystemClock.elapsedRealtime()
            synchronized(this) {
                if (lastAutomaticAttemptElapsed != 0L &&
                    now - lastAutomaticAttemptElapsed in 0L..20_000L)
                    return "已尝试恢复，正在等待系统结果"
                lastAutomaticAttemptElapsed = now
            }
            return startRequested(context)
        }

        private fun startRequested(context: Context): String {
            startupFailure = null
            lastStartRequestElapsed = SystemClock.elapsedRealtime()
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
            BackgroundConsentStore.set(context, false)
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
            BackgroundConsentStore.set(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (BackgroundConsentStore.get(this) != BackgroundConsent.ENABLED) {
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

        // A still-attached but stalled executor is not proof of a healthy service.
        // A new explicit/recovery start request may rebuild it once; no restart loop.
        if (scheduler != null && !runtimeProbe().healthy &&
            attachedAtElapsed > 0L &&
            SystemClock.elapsedRealtime() - attachedAtElapsed > 150_000L) {
            scheduler?.shutdownNow()
            scheduler = null
            S1Runtime.detachService()
        }
        if (scheduler == null) {
            try {
                S1Runtime.attachService(applicationContext)
                attachedAtElapsed = SystemClock.elapsedRealtime()
                lastTickElapsed = 0L
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
                        // Core listener health is independent of optional desktop-widget I/O.
                        lastTickElapsed = SystemClock.elapsedRealtime()
                        runCatching {
                            GuardWidgetPublisher.publishFromManager(applicationContext, manager)
                        }.onFailure { widgetError ->
                            Log.w("OpenKinGuard", "Background widget refresh failed", widgetError)
                        }
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
        lastTickElapsed = 0L
        attachedAtElapsed = 0L
        lastStartRequestElapsed = 0L
        S1Runtime.detachService()
        if (!S1Runtime.isUiVisible()) GuardWidgetPublisher.markInactive(applicationContext)
        super.onDestroy()
    }
}
