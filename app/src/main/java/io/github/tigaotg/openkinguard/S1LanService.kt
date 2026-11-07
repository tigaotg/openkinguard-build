package io.github.tigaotg.openkinguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicitly enabled connected-device foreground service. Android does not guarantee 24/7 execution. */
class S1LanService : Service() {
    companion object {
        const val ACTION_START = "io.github.tigaotg.openkinguard.START_LAN_TEST"
        const val ACTION_STOP = "io.github.tigaotg.openkinguard.STOP_LAN_TEST"
        private const val CHANNEL_ID = "okg_s2_connected_device_v1"
        private const val NOTIFICATION_ID = 205
        private const val STARTING_WINDOW_MS = 15_000L
        private const val TICK_INTERVAL_SECONDS = 60L
        private val tickInProgress = AtomicBoolean(false)

        @Volatile private var startupFailure: String? = null

        data class Probe(
            val healthy: Boolean,
            val starting: Boolean,
            val failure: String?,
            val health: BackgroundHealthSnapshot
        )

        /** Local service health is independent of network availability and remote peer reachability. */
        fun runtimeProbe(): Probe {
            val now = SystemClock.elapsedRealtime()
            BackgroundMonitorHealth.setTcpListenerState(S1Runtime.tcpListenerState())
            val health = BackgroundMonitorHealth.snapshot(now)
            val attached = S1Runtime.isBackgroundListening()
            val healthy = attached && BackgroundMonitorHealthPolicy.localServiceHealthy(health)
            val pendingState = health.serviceState in setOf(
                BackgroundServiceState.START_REQUESTED,
                BackgroundServiceState.STARTING,
                BackgroundServiceState.RESTART_REQUESTED
            )
            val initializingFirstTick = health.serviceState == BackgroundServiceState.RUNNING &&
                health.schedulerState == BackgroundSchedulerState.DELAYED &&
                now - health.serviceStateElapsed in 0L..STARTING_WINDOW_MS
            val activeTick = health.taskRunning && !health.taskTimedOut
            val starting = !healthy && (
                (pendingState && now - health.serviceStateElapsed in 0L..STARTING_WINDOW_MS) ||
                    initializingFirstTick || activeTick
                )
            val failure = startupFailure ?: health.lastErrorDetail ?: when {
                health.serviceState == BackgroundServiceState.BLOCKED_BY_SYSTEM ->
                    "系统阻止了前台服务启动"
                health.serviceState == BackgroundServiceState.START_FAILED ->
                    "后台服务启动失败"
                !attached && health.serviceState == BackgroundServiceState.STOPPED_BY_USER ->
                    "用户已关闭后台监听"
                !attached -> "后台服务尚未附着到共享运行时"
                health.tcpListenerState != BackgroundTcpListenerState.LISTENING ->
                    "本机 TCP 监听：${health.tcpListenerState.displayLabel}"
                health.schedulerState != BackgroundSchedulerState.HEALTHY ->
                    "本机调度器：${health.schedulerState.displayLabel}"
                else -> null
            }
            return Probe(healthy, starting, failure, health)
        }

        /** Only an explicit user click can enable or re-enable background listening. */
        fun requestStart(context: Context): String {
            BackgroundConsentStore.set(context, true)
            BackgroundMonitorHealth.resetRecoveryAttempts()
            BackgroundMonitorHealth.recordRecoveryTrigger(BackgroundRecoveryTrigger.USER_REQUEST)
            return startRequested(context, automatic = false)
        }

        /** A bounded recovery request for users who previously opted in. */
        fun requestAutoRecovery(
            context: Context,
            trigger: BackgroundRecoveryTrigger = BackgroundRecoveryTrigger.APP_FOREGROUND
        ): String {
            val consent = BackgroundConsentStore.get(context)
            if (consent != BackgroundConsent.ENABLED)
                return "没有有效的后台监听授权，不会自动启动"
            BackgroundMonitorHealth.recordRecoveryTrigger(trigger)
            val probe = runtimeProbe()
            if (probe.healthy) return "本机后台服务正常运行"
            if (probe.starting) return "后台监听正在启动，暂不重复提交请求"

            val now = SystemClock.elapsedRealtime()
            val decision = BackgroundMonitorHealth.beginRecoveryAttempt(
                consent, healthy = false, nowElapsed = now
            )
            if (!decision.allowed) {
                return when {
                    decision.exhausted ->
                        "自动恢复已达到 ${BackgroundRecoveryPolicy.MAX_AUTOMATIC_ATTEMPTS} 次上限，请打开应用后手动重试"
                    else -> "自动恢复正在退避，请等待 ${decision.retryAfterMs / 1000} 秒"
                }
            }
            return startRequested(context, automatic = true)
        }

        private fun startRequested(context: Context, automatic: Boolean): String {
            startupFailure = null
            val now = SystemClock.elapsedRealtime()
            BackgroundMonitorHealth.markStartRequested(automatic, now)
            return runCatching {
                val intent = Intent(context, S1LanService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
                if (automatic) "正在请求系统恢复后台监听"
                else "正在开启持续监听，等待系统确认"
            }.getOrElse { failure ->
                val blocked = failure.javaClass.simpleName.contains(
                    "ForegroundServiceStartNotAllowedException"
                )
                val detail = if (blocked)
                    "系统限制阻止后台前台服务启动：${failure.javaClass.simpleName}"
                else "系统拒绝启动：${failure.javaClass.simpleName}"
                startupFailure = detail
                BackgroundMonitorHealth.markStartupFailure(
                    BackgroundStartupStage.FOREGROUND_START,
                    if (blocked) "FOREGROUND_START_BLOCKED" else "FOREGROUND_START_FAILED",
                    detail,
                    blockedBySystem = blocked,
                    nowElapsed = SystemClock.elapsedRealtime()
                )
                detail
            }
        }

        fun requestStop(context: Context): String = runCatching {
            startupFailure = null
            BackgroundConsentStore.set(context, false)
            val stopped = context.stopService(Intent(context, S1LanService::class.java))
            if (!stopped) {
                BackgroundMonitorHealth.markSchedulerStopped()
                BackgroundMonitorHealth.markServiceStopped(
                    userInitiated = true, nowElapsed = SystemClock.elapsedRealtime()
                )
            }
            "正在停止持续监听，等待系统确认"
        }.getOrElse { failure ->
            "停止请求失败：${failure.javaClass.simpleName}"
        }

        fun lastStartupFailure(): String? =
            startupFailure ?: BackgroundMonitorHealth.snapshot(
                SystemClock.elapsedRealtime()
            ).lastErrorDetail
    }

    private var scheduler: ScheduledExecutorService? = null
    private var schedulerStartedElapsed = 0L
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BackgroundConsentStore.set(this, false)
            scheduler?.shutdownNow()
            scheduler = null
            BackgroundMonitorHealth.markSchedulerStopped()
            BackgroundMonitorHealth.markServiceStopped(
                userInitiated = true, nowElapsed = SystemClock.elapsedRealtime()
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val consent = BackgroundConsentStore.get(this)
        if (consent != BackgroundConsent.ENABLED) {
            BackgroundMonitorHealth.markServiceStopped(
                userInitiated = consent == BackgroundConsent.USER_DISABLED,
                nowElapsed = SystemClock.elapsedRealtime()
            )
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) {
            BackgroundMonitorHealth.recordRecoveryTrigger(
                BackgroundRecoveryTrigger.SYSTEM_STICKY_RESTART
            )
        }

        val existingScheduler = scheduler
        if (existingScheduler != null) {
            val probe = runtimeProbe()
            if (probe.healthy || probe.starting) return START_STICKY
            if (!stopSchedulerForRestart()) {
                recordStartupFailure(
                    BackgroundStartupStage.SCHEDULER_START,
                    "PREVIOUS_SCHEDULER_STILL_RUNNING",
                    IllegalStateException("Previous scheduler did not terminate within 500ms"),
                    blockedBySystem = false
                )
                stopSelf()
                return START_NOT_STICKY
            }
        }

        BackgroundMonitorHealth.markServiceStarting(SystemClock.elapsedRealtime())
        startupFailure = null
        val notification = try {
            createForegroundNotification()
        } catch (error: Exception) {
            recordStartupFailure(
                BackgroundStartupStage.NOTIFICATION_CREATE,
                "NOTIFICATION_CREATE_FAILED",
                error,
                blockedBySystem = false
            )
            stopSelf()
            return START_NOT_STICKY
        }
        BackgroundMonitorHealth.markStartupStage(BackgroundStartupStage.FOREGROUND_START)
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (error: Exception) {
            recordStartupFailure(
                BackgroundStartupStage.FOREGROUND_START,
                "FOREGROUND_START_FAILED",
                error,
                blockedBySystem = isSystemStartBlocked(error)
            )
            stopSelf()
            return START_NOT_STICKY
        }

        registerNetworkMonitor()

        var stage = BackgroundStartupStage.MANAGER_INITIALIZATION
        var attached = false
        try {
            BackgroundMonitorHealth.markStartupStage(stage)
            val manager = S1Runtime.manager(applicationContext)

            stage = BackgroundStartupStage.TCP_LISTENER_BIND
            BackgroundMonitorHealth.markStartupStage(stage)
            S1Runtime.attachService(applicationContext)
            attached = true
            var tcpState = manager.tcpListenerHealth()
            var waitCount = 0
            while (tcpState == BackgroundTcpListenerState.BINDING && waitCount < 10) {
                Thread.sleep(25L)
                tcpState = manager.tcpListenerHealth()
                waitCount++
            }
            BackgroundMonitorHealth.setTcpListenerState(tcpState)
            if (tcpState != BackgroundTcpListenerState.LISTENING) {
                throw IllegalStateException("TCP listener state: ${tcpState.name}")
            }

            stage = BackgroundStartupStage.SCHEDULER_START
            BackgroundMonitorHealth.markStartupStage(stage)
            val executor = Executors.newSingleThreadScheduledExecutor { job ->
                Thread(job, "okg-s2-background-heartbeat").apply { isDaemon = true }
            }
            scheduler = executor
            schedulerStartedElapsed = SystemClock.elapsedRealtime()
            BackgroundMonitorHealth.markSchedulerStarted(schedulerStartedElapsed)
            executor.scheduleWithFixedDelay(
                { runScheduledTick() }, 0L, TICK_INTERVAL_SECONDS, TimeUnit.SECONDS
            )
            BackgroundMonitorHealth.markServiceRunning(SystemClock.elapsedRealtime())
            return START_STICKY
        } catch (error: Exception) {
            recordStartupFailure(
                stage,
                when (stage) {
                    BackgroundStartupStage.TCP_LISTENER_BIND -> "TCP_LISTENER_START_FAILED"
                    BackgroundStartupStage.SCHEDULER_START -> "SCHEDULER_START_FAILED"
                    else -> "MANAGER_INITIALIZATION_FAILED"
                },
                error,
                blockedBySystem = false
            )
            scheduler?.shutdownNow()
            scheduler = null
            BackgroundMonitorHealth.markSchedulerStopped()
            if (attached) S1Runtime.detachService()
            unregisterNetworkMonitor()
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun createForegroundNotification(): Notification {
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "持续通信监听", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "由用户主动开启的已绑定设备通信；不保证系统始终在线"
            })
        }
        val activityIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, S1LanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("OpenKinGuard · 本机监听服务")
            .setContentText("本机服务状态可检查；不代表家属设备在线")
            .setContentIntent(activityIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止监听", stopIntent).build())
            .build()
    }

    private fun registerNetworkMonitor() {
        if (networkCallback != null) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                BackgroundMonitorHealth.setNetworkState(BackgroundNetworkState.NETWORK_CHANGED)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (manager.activeNetwork == network) {
                    BackgroundMonitorHealth.setNetworkState(
                        BackgroundNetworkState.NETWORK_AVAILABLE
                    )
                }
            }

            override fun onLost(network: Network) {
                BackgroundMonitorHealth.setNetworkState(
                    if (manager.activeNetwork == null) BackgroundNetworkState.NO_NETWORK
                    else BackgroundNetworkState.NETWORK_CHANGED
                )
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            connectivityManager = manager
            networkCallback = callback
            BackgroundMonitorHealth.setNetworkState(
                if (manager.activeNetwork == null) BackgroundNetworkState.NO_NETWORK
                else BackgroundNetworkState.NETWORK_AVAILABLE
            )
        } catch (error: Exception) {
            BackgroundMonitorHealth.setNetworkState(BackgroundNetworkState.UNKNOWN)
            Log.w("OpenKinGuard", "Cannot observe default network state", error)
        }
    }

    private fun unregisterNetworkMonitor() {
        val manager = connectivityManager
        val callback = networkCallback
        if (manager != null && callback != null) {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
        connectivityManager = null
        networkCallback = null
    }

    private fun stopSchedulerForRestart(): Boolean {
        val current = scheduler ?: return true
        current.shutdownNow()
        val terminated = runCatching {
            current.awaitTermination(500L, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!terminated) return false
        scheduler = null
        BackgroundMonitorHealth.markSchedulerStopped()
        return true
    }

    private fun runScheduledTick() {
        if (!tickInProgress.compareAndSet(false, true)) return
        val startedAt = SystemClock.elapsedRealtime()
        val generation = BackgroundMonitorHealth.beginTick(startedAt)
        if (generation == null) {
            tickInProgress.set(false)
            return
        }
        try {
            val manager = S1Runtime.manager(applicationContext)
            manager.probeAsync()
            manager.expireOldIncoming()
            manager.deliverPending()
            if (startedAt - schedulerStartedElapsed >= 60_000L) {
                manager.checkLiveness()
            }
            val tcpState = manager.tcpListenerHealth()
            BackgroundMonitorHealth.setTcpListenerState(tcpState)
            if (tcpState != BackgroundTcpListenerState.LISTENING) {
                throw IllegalStateException("TCP listener health: ${tcpState.name}")
            }
            val completedAt = SystemClock.elapsedRealtime()
            BackgroundMonitorHealth.finishTick(generation, completedAt)
            // Widget I/O is optional and must not invalidate the core scheduler tick.
            runCatching {
                GuardWidgetPublisher.publishFromManager(applicationContext, manager)
            }.onFailure { widgetError ->
                Log.w("OpenKinGuard", "Background widget refresh failed", widgetError)
            }
        } catch (error: Exception) {
            val code = if (error.message?.contains("TCP listener") == true)
                "TCP_LISTENER_UNHEALTHY" else "BACKGROUND_TASK_FAILED"
            BackgroundMonitorHealth.failTick(
                generation, code,
                error.message ?: error.javaClass.simpleName,
                SystemClock.elapsedRealtime()
            )
            Log.w("OpenKinGuard", "Background listener tick failed", error)
        } finally {
            BackgroundMonitorHealth.abandonTick(
                generation, SystemClock.elapsedRealtime()
            )
            tickInProgress.set(false)
        }
    }

    private fun recordStartupFailure(
        stage: BackgroundStartupStage,
        code: String,
        error: Exception,
        blockedBySystem: Boolean
    ) {
        val detail = when {
            blockedBySystem -> "系统限制阻止前台服务启动：${error.javaClass.simpleName}"
            stage == BackgroundStartupStage.NOTIFICATION_CREATE ->
                "前台通知创建失败：${error.javaClass.simpleName}"
            stage == BackgroundStartupStage.FOREGROUND_START ->
                "前台服务启动失败：${error.javaClass.simpleName}"
            stage == BackgroundStartupStage.TCP_LISTENER_BIND ->
                "本机 TCP 监听启动失败：${error.message ?: error.javaClass.simpleName}"
            else -> "后台服务在 ${stage.name} 阶段失败：${error.message ?: error.javaClass.simpleName}"
        }
        startupFailure = detail
        BackgroundMonitorHealth.markStartupFailure(
            stage, code, detail, blockedBySystem, SystemClock.elapsedRealtime()
        )
        Log.w("OpenKinGuard", detail, error)
    }

    private fun isSystemStartBlocked(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause.javaClass.simpleName == "ForegroundServiceStartNotAllowedException")
                return true
            cause = cause.cause
        }
        return false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // stopWithTask=false: removing the activity task does not revoke user consent
        // or stop an explicitly enabled foreground service.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scheduler?.shutdownNow()
        scheduler = null
        BackgroundMonitorHealth.markSchedulerStopped()
        unregisterNetworkMonitor()
        S1Runtime.detachService()
        val previous = BackgroundMonitorHealth.snapshot(SystemClock.elapsedRealtime())
        if (previous.serviceState !in setOf(
                BackgroundServiceState.START_FAILED,
                BackgroundServiceState.BLOCKED_BY_SYSTEM,
                BackgroundServiceState.STOPPED_BY_USER
            )) {
            BackgroundMonitorHealth.markServiceStopped(
                userInitiated = BackgroundConsentStore.get(this) ==
                    BackgroundConsent.USER_DISABLED,
                nowElapsed = SystemClock.elapsedRealtime()
            )
        }
        if (!S1Runtime.isUiVisible()) {
            GuardWidgetPublisher.markInactive(applicationContext)
        }
        super.onDestroy()
    }
}
