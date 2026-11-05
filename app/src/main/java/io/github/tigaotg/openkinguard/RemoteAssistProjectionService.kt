package io.github.tigaotg.openkinguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One manually approved MediaProjection session. WebRTC ScreenCapturerAndroid
 * owns the projection token and virtual display; no bespoke JPEG/AVC path.
 * The user can revoke capture with the persistent Android notification.
 */
class RemoteAssistProjectionService : Service() {
    private val closing = AtomicBoolean(false)
    private var sessionId: String = ""
    private val main = Handler(Looper.getMainLooper())
    private val diagnosticWorker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "okg-rtc-diagnostic").apply { isDaemon = true }
    }
    private val authorizationWatchdog = object : Runnable {
        override fun run() {
            if (closing.get()) return
            val manager = S1Runtime.manager(applicationContext)
            if (sessionId.isNotBlank() && !manager.authorizedRemoteControl(sessionId)) {
                requestStop()
            } else main.postDelayed(this, 5_000L)
        }
    }
    private val diagnosticTicker = object : Runnable {
        override fun run() {
            if (closing.get() || sessionId.isBlank()) return
            val id = sessionId
            val report = RemoteAssistDiagnostics.peerSummary(
                AppVersionInfo.name(applicationContext))
            runCatching {
                diagnosticWorker.execute {
                    runCatching {
                        S1Runtime.manager(applicationContext).sendRemoteAssistDiagnostic(id, report)
                    }.onFailure {
                        RemoteAssistDiagnostics.warning("诊断回传-" + it.javaClass.simpleName)
                    }
                }
            }
            main.postDelayed(this, 8_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        if (sessionId.isNotEmpty() || closing.get()) return START_NOT_STICKY
        val requestedId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val permissionData: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (requestedId.isBlank() || code != android.app.Activity.RESULT_OK ||
            permissionData == null) {
            transferStatus = "未获得新的安卓屏幕共享授权"
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = S1Runtime.manager(applicationContext)
        createChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (error: Exception) {
            transferStatus = "无法启动屏幕共享前台服务：" + error.javaClass.simpleName
            stopSelf()
            return START_NOT_STICKY
        }
        if (!manager.screenShareStarted(requestedId)) {
            transferStatus = "本次协助授权已失效，请重新申请"
            stopSelf()
            return START_NOT_STICKY
        }
        sessionId = requestedId
        try {
            // Do not call getMediaProjection here: Android 14+ allows a one-use token.
            // WebRTC ScreenCapturerAndroid consumes it exactly once.
            S1Runtime.attachAssistForeground(applicationContext)
            val metrics = resources.displayMetrics
            val w = metrics.widthPixels.coerceAtLeast(100)
            val h = metrics.heightPixels.coerceAtLeast(100)
            val scale = minOf(1f, 720f / minOf(w, h), 1280f / maxOf(w, h))
            val width = (((w * scale).toInt() / 16) * 16).coerceAtLeast(128)
            val height = (((h * scale).toInt() / 16) * 16).coerceAtLeast(128)
            manager.startRemoteRtcScreen(requestedId, permissionData, width, height)
            transferStatus = "WebRTC 屏幕采集已请求，正在进行 P2P 信令交换"
            RemoteAssistDiagnostics.mode("WebRTC 视频 / 自动选择硬件或软件编码")
            RemoteAssistDiagnostics.size(width, height)
            main.postDelayed(authorizationWatchdog, 5_000L)
            main.postDelayed(diagnosticTicker, 6_000L)
        } catch (error: Exception) {
            transferStatus = "WebRTC 启动失败：" + error.javaClass.simpleName
            RemoteAssistDiagnostics.error("WebRTC服务-" + error.javaClass.simpleName)
            requestStop()
        }
        return START_NOT_STICKY
    }

    private fun requestStop() {
        if (!closing.compareAndSet(false, true)) return
        val id = sessionId
        if (id.isNotBlank()) {
            Thread({ runCatching {
                val manager = S1Runtime.manager(applicationContext)
                if (manager.authorizedRemoteControl(id)) manager.stopRemoteAssist()
            } }, "okg-rtc-revoke").apply { isDaemon = true; start() }
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (!closing.get() && sessionId.isNotBlank()) requestStop()
        closing.set(true)
        main.removeCallbacks(authorizationWatchdog)
        main.removeCallbacks(diagnosticTicker)
        diagnosticWorker.shutdownNow()
        S1Runtime.detachAssistForeground()
        transferStatus = "本次屏幕共享已结束"
        super.onDestroy()
    }

    private fun createChannel() {
        val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            "WebRTC 远程协助屏幕共享", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun buildNotification(): Notification {
        val stop = Intent(this, RemoteAssistProjectionService::class.java).apply {
            action = ACTION_STOP
        }
        val pending = PendingIntent.getService(this, 1719, stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("OpenKinGuard 正在通过 WebRTC 共享屏幕")
            .setContentText("仅限本次已同意的协助，随时可停止")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "立即停止协助", pending)
            .build()
    }

    companion object {
        @Volatile private var transferStatus = "尚未开启屏幕共享"
        fun currentTransferStatus(): String = transferStatus
        fun setTransferStatus(value: String) { transferStatus = value.take(150) }
        const val EXTRA_SESSION_ID = "io.github.tigaotg.openkinguard.ASSIST_SESSION"
        const val EXTRA_RESULT_CODE = "io.github.tigaotg.openkinguard.ASSIST_CODE"
        const val EXTRA_RESULT_DATA = "io.github.tigaotg.openkinguard.ASSIST_GRANT"
        private const val CHANNEL = "openkinguard-assist-projection-v2"
        private const val ACTION_STOP = "io.github.tigaotg.openkinguard.STOP_ASSIST"
        private const val NOTIFICATION_ID = 3317
    }
}
