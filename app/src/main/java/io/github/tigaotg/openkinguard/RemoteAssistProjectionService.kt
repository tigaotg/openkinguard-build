package io.github.tigaotg.openkinguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Voluntary screen sharing. Every service start uses a NEW Android consent result.
 * Media never touches app storage; a notification provides a one-tap stop control.
 * There is no boot receiver or unattended capture path.
 */
class RemoteAssistProjectionService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "okg-assist-jpeg").apply { isDaemon = true }
    }
    private val sending = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private var lastFrameAt = 0L
    private var sessionId: String = ""
    private val main = Handler(Looper.getMainLooper())
    private val authorizationWatchdog = object : Runnable {
        override fun run() {
            if (closing.get()) return
            if (sessionId.isNotEmpty() &&
                !S1Runtime.manager(applicationContext).authorizedRemoteControl(sessionId)) {
                requestStop()
            } else main.postDelayed(this, 5_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop(); return START_NOT_STICKY
        }
        if (projection != null || closing.get()) return START_NOT_STICKY
        val requestedId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (requestedId.isBlank() || code != android.app.Activity.RESULT_OK || resultData == null) {
            transferStatus = "屏幕共享授权信息不完整"
            stopSelf(); return START_NOT_STICKY
        }
        createChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (error: Exception) {
            transferStatus = "系统前台服务无法启动：${error.javaClass.simpleName}"
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = S1Runtime.manager(applicationContext)
        if (!manager.screenShareStarted(requestedId)) {
            transferStatus = "远程协助请求已经失效，请重新申请"
            stopSelf(); return START_NOT_STICKY
        }
        sessionId = requestedId
        transferStatus = "系统已授权，正在准备画面"
        main.postDelayed(authorizationWatchdog, 5_000L)
        try {
            // The user has explicitly approved this one projection. Keeping its
            // foreground service alive lets family members assist on other apps;
            // it does not grant any unattended future sessions.
            S1Runtime.attachAssistForeground(applicationContext)
            val service = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val active = requireNotNull(service.getMediaProjection(code, resultData))
            projection = active
            active.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { requestStop() }
            }, Handler(mainLooper))

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels.coerceAtLeast(100)
            val height = metrics.heightPixels.coerceAtLeast(100)
            val dpi = metrics.densityDpi
            captureThread = HandlerThread("okg-assist-screen").apply { start() }
            val handler = Handler(requireNotNull(captureThread).looper)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader?.setOnImageAvailableListener({ queue ->
                val image = runCatching { queue.acquireLatestImage() }.getOrNull()
                    ?: return@setOnImageAvailableListener
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (!manager.authorizedRemoteControl(requestedId)) {
                        requestStop()
                        return@setOnImageAvailableListener
                    }
                    if (closing.get() || now - lastFrameAt < 1000 ||
                        !sending.compareAndSet(false, true)) return@setOnImageAvailableListener
                    lastFrameAt = now
                    val plane = image.planes.firstOrNull()
                    if (plane == null) {
                        sending.set(false)
                        return@setOnImageAvailableListener
                    }
                    val widthWithPadding = width +
                        (plane.rowStride - plane.pixelStride * width) / plane.pixelStride
                    val buffer = Bitmap.createBitmap(widthWithPadding, height,
                        Bitmap.Config.ARGB_8888)
                    buffer.copyPixelsFromBuffer(plane.buffer)
                    val cropped = Bitmap.createBitmap(buffer, 0, 0, width, height)
                    buffer.recycle()
                    val scale = minOf(1f, 420f / width, 800f / height)
                    val targetWidth = (width * scale).toInt().coerceAtLeast(100)
                    val targetHeight = (height * scale).toInt().coerceAtLeast(100)
                    val scaled = Bitmap.createScaledBitmap(cropped,
                        targetWidth, targetHeight, true)
                    if (scaled !== cropped) cropped.recycle()
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 38, out)
                    if (out.size() > 75_000) {
                        out.reset()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 25, out)
                    }
                    scaled.recycle()
                    val bytes = out.toByteArray()
                    worker.execute {
                        try {
                            if (!closing.get() && bytes.size in 100..75_000) {
                                manager.sendRemoteAssistFrame(requestedId,
                                    targetWidth, targetHeight, bytes)
                                transferStatus = "画面已通过直连发送"
                            }
                        } catch (error: Exception) {
                            // Never fall back to relays. Show a local reason instead of hiding it.
                            transferStatus = "画面发送失败：${error.message?.take(100)
                                ?: error.javaClass.simpleName}"
                        } finally { sending.set(false) }
                    }
                } catch (error: Exception) {
                    transferStatus = "画面处理失败：${error.javaClass.simpleName}"
                    sending.set(false)
                } finally { image.close() }
            }, handler)
            virtualDisplay = active.createVirtualDisplay("OpenKinGuard-assisted-screen",
                width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                requireNotNull(reader).surface, null, handler)
        } catch (error: Exception) {
            transferStatus = "屏幕捕获启动失败：${error.javaClass.simpleName}"
            requestStop()
        }
        return START_NOT_STICKY
    }

    private fun requestStop() {
        if (!closing.compareAndSet(false, true)) return
        val id = sessionId
        // Cancel authorization locally even if connectivity has failed.
        if (id.isNotEmpty()) {
            Thread({ runCatching {
                val manager = S1Runtime.manager(applicationContext)
                if (manager.authorizedRemoteControl(id)) manager.stopRemoteAssist()
            } }, "okg-assist-revoke").apply { isDaemon = true; start() }
        }
        stopSelf()
    }

    override fun onDestroy() {
        // OS may destroy the service without the notification stop action.
        if (!closing.get() && sessionId.isNotEmpty()) requestStop()
        closing.set(true)
        main.removeCallbacks(authorizationWatchdog)
        reader?.setOnImageAvailableListener(null, null)
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        captureThread?.quitSafely()
        worker.shutdownNow()
        S1Runtime.detachAssistForeground()
        if (transferStatus == "画面已通过直连发送") transferStatus = "共享已经结束"
        super.onDestroy()
    }

    private fun createChannel() {
        val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            "远程协助屏幕共享", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun buildNotification(): Notification {
        val stop = Intent(this, RemoteAssistProjectionService::class.java).apply {
            action = ACTION_STOP
        }
        val pending = PendingIntent.getService(this, 1719, stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("OpenKinGuard 正在共享屏幕")
            .setContentText("仅限本次已同意的家人协助，可随时停止")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "立即停止协助", pending)
            .build()
    }

    companion object {
        @Volatile private var transferStatus = "尚未开启屏幕共享"
        fun currentTransferStatus(): String = transferStatus
        const val EXTRA_SESSION_ID = "io.github.tigaotg.openkinguard.ASSIST_SESSION"
        const val EXTRA_RESULT_CODE = "io.github.tigaotg.openkinguard.ASSIST_CODE"
        const val EXTRA_RESULT_DATA = "io.github.tigaotg.openkinguard.ASSIST_GRANT"
        private const val CHANNEL = "openkinguard-assist-projection-v1"
        private const val ACTION_STOP = "io.github.tigaotg.openkinguard.STOP_ASSIST"
        private const val NOTIFICATION_ID = 3317
    }
}
