package io.github.tigaotg.openkinguard

/** Local process/service health only. None of these states means a family peer is reachable. */
internal enum class BackgroundServiceState {
    STOPPED, START_REQUESTED, STARTING, RUNNING, RESTART_REQUESTED,
    START_FAILED, STOPPED_BY_USER, BLOCKED_BY_SYSTEM
}

internal enum class BackgroundStartupStage {
    NONE, PERMISSION_CHECK, NOTIFICATION_CREATE, FOREGROUND_START,
    MANAGER_INITIALIZATION, TCP_LISTENER_BIND, SCHEDULER_START
}

internal enum class BackgroundSchedulerState {
    NOT_STARTED, HEALTHY, DELAYED, STALLED, TASK_FAILED
}

internal enum class BackgroundTcpListenerState {
    NOT_STARTED, BINDING, LISTENING, FAILED, CLOSED_UNEXPECTEDLY
}

internal enum class BackgroundNetworkState {
    NO_NETWORK, NETWORK_AVAILABLE, NETWORK_CHANGED, UNKNOWN
}

internal enum class BackgroundRecoveryTrigger {
    NONE, USER_REQUEST, APP_FOREGROUND, BOOT_COMPLETED, PACKAGE_REPLACED,
    SYSTEM_STICKY_RESTART
}

internal enum class BackgroundProcessExitKind {
    NOT_AVAILABLE, SYSTEM_LOW_MEMORY, CRASH, ANR, USER_REQUESTED, USER_STOPPED,
    PACKAGE_UPDATED, OTHER, CHECK_FAILED
}

internal data class BackgroundProcessExitSnapshot(
    val kind: BackgroundProcessExitKind = BackgroundProcessExitKind.NOT_AVAILABLE,
    val timestamp: Long = 0L,
    val detail: String? = null
)

internal data class BackgroundHealthSnapshot(
    val serviceState: BackgroundServiceState = BackgroundServiceState.STOPPED,
    val serviceStateElapsed: Long = 0L,
    val startupStage: BackgroundStartupStage = BackgroundStartupStage.NONE,
    val schedulerState: BackgroundSchedulerState = BackgroundSchedulerState.NOT_STARTED,
    val schedulerStartedElapsed: Long = 0L,
    val schedulerGeneration: Long = 0L,
    val schedulerClosed: Boolean = true,
    val lastTickElapsed: Long = 0L,
    val lastTickStartedElapsed: Long = 0L,
    val lastTickEndedElapsed: Long = 0L,
    val lastSuccessfulTaskElapsed: Long = 0L,
    val taskRunning: Boolean = false,
    val taskTimedOut: Boolean = false,
    val lastTaskErrorCode: String? = null,
    val lastTaskErrorElapsed: Long = 0L,
    val tcpListenerState: BackgroundTcpListenerState =
        BackgroundTcpListenerState.NOT_STARTED,
    val networkState: BackgroundNetworkState = BackgroundNetworkState.UNKNOWN,
    val lastErrorCode: String? = null,
    val lastErrorDetail: String? = null,
    val lastErrorElapsed: Long = 0L,
    val recoveryAttemptCount: Int = 0,
    val lastRecoveryElapsed: Long = 0L,
    val lastRecoveryTrigger: BackgroundRecoveryTrigger = BackgroundRecoveryTrigger.NONE
)

internal data class BackgroundRecoveryDecision(
    val allowed: Boolean,
    val retryAfterMs: Long = 0L,
    val exhausted: Boolean = false
)

/** Three attempts per process: immediate, then 20s and 60s backoff. */
internal object BackgroundRecoveryPolicy {
    const val MAX_AUTOMATIC_ATTEMPTS = 3
    const val MIN_RETRY_INTERVAL_MS = 20_000L
    const val MAX_RETRY_INTERVAL_MS = 60_000L

    fun evaluate(
        consent: BackgroundConsent,
        healthy: Boolean,
        attemptCount: Int,
        lastAttemptElapsed: Long,
        nowElapsed: Long
    ): BackgroundRecoveryDecision {
        if (consent != BackgroundConsent.ENABLED || healthy)
            return BackgroundRecoveryDecision(allowed = false)
        if (attemptCount >= MAX_AUTOMATIC_ATTEMPTS)
            return BackgroundRecoveryDecision(allowed = false, exhausted = true)
        val requiredDelay = when (attemptCount) {
            0 -> 0L
            1 -> MIN_RETRY_INTERVAL_MS
            else -> MAX_RETRY_INTERVAL_MS
        }
        val elapsed = (nowElapsed - lastAttemptElapsed).coerceAtLeast(0L)
        return if (elapsed < requiredDelay) {
            BackgroundRecoveryDecision(allowed = false,
                retryAfterMs = requiredDelay - elapsed)
        } else {
            BackgroundRecoveryDecision(allowed = true)
        }
    }
}

internal object BackgroundMonitorHealthPolicy {
    const val TICK_DELAYED_AFTER_MS = 90_000L
    const val TICK_STALLED_AFTER_MS = 150_000L
    const val TASK_TIMEOUT_AFTER_MS = 120_000L

    fun schedulerState(state: BackgroundHealthSnapshot, nowElapsed: Long):
        BackgroundSchedulerState {
        if (state.schedulerClosed || state.schedulerStartedElapsed <= 0L)
            return BackgroundSchedulerState.NOT_STARTED
        if (state.lastTaskErrorCode != null &&
            state.lastTaskErrorElapsed >= state.lastSuccessfulTaskElapsed)
            return BackgroundSchedulerState.TASK_FAILED
        if (state.taskRunning && state.lastTickStartedElapsed > 0L &&
            nowElapsed - state.lastTickStartedElapsed >= TASK_TIMEOUT_AFTER_MS)
            return BackgroundSchedulerState.STALLED
        if (state.lastTickElapsed <= 0L) {
            return if (nowElapsed - state.schedulerStartedElapsed >= TICK_STALLED_AFTER_MS)
                BackgroundSchedulerState.STALLED else BackgroundSchedulerState.DELAYED
        }
        val age = (nowElapsed - state.lastTickElapsed).coerceAtLeast(0L)
        return when {
            age <= TICK_DELAYED_AFTER_MS -> BackgroundSchedulerState.HEALTHY
            age <= TICK_STALLED_AFTER_MS -> BackgroundSchedulerState.DELAYED
            else -> BackgroundSchedulerState.STALLED
        }
    }

    /** Network availability is deliberately excluded from local service health. */
    fun localServiceHealthy(state: BackgroundHealthSnapshot): Boolean =
        state.serviceState == BackgroundServiceState.RUNNING &&
            state.schedulerState == BackgroundSchedulerState.HEALTHY &&
            state.tcpListenerState == BackgroundTcpListenerState.LISTENING
}

internal object BackgroundMonitorHealth {
    @Volatile private var current = BackgroundHealthSnapshot()

    fun snapshot(nowElapsed: Long): BackgroundHealthSnapshot {
        val raw = current
        val timedOut = raw.taskRunning && raw.lastTickStartedElapsed > 0L &&
            nowElapsed - raw.lastTickStartedElapsed >=
                BackgroundMonitorHealthPolicy.TASK_TIMEOUT_AFTER_MS
        return raw.copy(
            schedulerState = BackgroundMonitorHealthPolicy.schedulerState(raw, nowElapsed),
            taskTimedOut = timedOut
        )
    }

    @Synchronized fun markStartRequested(automatic: Boolean, nowElapsed: Long) {
        current = current.copy(
            serviceState = if (automatic) BackgroundServiceState.RESTART_REQUESTED
                else BackgroundServiceState.START_REQUESTED,
            serviceStateElapsed = nowElapsed,
            startupStage = BackgroundStartupStage.NONE,
            lastErrorCode = null,
            lastErrorDetail = null,
            lastErrorElapsed = 0L
        )
    }

    @Synchronized fun markServiceStarting(nowElapsed: Long) {
        current = current.copy(
            serviceState = BackgroundServiceState.STARTING,
            serviceStateElapsed = nowElapsed,
            startupStage = BackgroundStartupStage.NOTIFICATION_CREATE,
            lastErrorCode = null,
            lastErrorDetail = null,
            lastErrorElapsed = 0L
        )
    }

    @Synchronized fun markServiceRunning(nowElapsed: Long) {
        current = current.copy(
            serviceState = BackgroundServiceState.RUNNING,
            serviceStateElapsed = nowElapsed,
            startupStage = BackgroundStartupStage.NONE,
            lastErrorCode = null,
            lastErrorDetail = null,
            lastErrorElapsed = 0L
        )
    }

    @Synchronized fun markStartupStage(stage: BackgroundStartupStage) {
        current = current.copy(startupStage = stage)
    }

    @Synchronized fun markStartupFailure(
        stage: BackgroundStartupStage,
        code: String,
        detail: String,
        blockedBySystem: Boolean,
        nowElapsed: Long
    ) {
        current = current.copy(
            serviceState = if (blockedBySystem) BackgroundServiceState.BLOCKED_BY_SYSTEM
                else BackgroundServiceState.START_FAILED,
            serviceStateElapsed = nowElapsed,
            startupStage = stage,
            lastErrorCode = code,
            lastErrorDetail = detail,
            lastErrorElapsed = nowElapsed
        )
    }

    @Synchronized fun markServiceStopped(userInitiated: Boolean, nowElapsed: Long) {
        current = current.copy(
            serviceState = if (userInitiated) BackgroundServiceState.STOPPED_BY_USER
                else BackgroundServiceState.STOPPED,
            serviceStateElapsed = nowElapsed,
            startupStage = BackgroundStartupStage.NONE
        )
    }

    @Synchronized fun markSchedulerStarted(nowElapsed: Long) {
        current = current.copy(
            schedulerState = BackgroundSchedulerState.DELAYED,
            schedulerStartedElapsed = nowElapsed,
            schedulerGeneration = current.schedulerGeneration + 1L,
            schedulerClosed = false,
            lastTickElapsed = 0L,
            lastTickStartedElapsed = 0L,
            lastTickEndedElapsed = 0L,
            lastSuccessfulTaskElapsed = 0L,
            // A previous scheduler task may still be unwinding after shutdownNow().
            // Keep its lease until finishTick/failTick observes its actual completion.
            lastTickStartedElapsed = if (current.taskRunning)
                current.lastTickStartedElapsed else 0L,
            taskRunning = current.taskRunning,
            taskTimedOut = current.taskTimedOut,
            lastTaskErrorCode = null,
            lastTaskErrorElapsed = 0L
        )
    }

    @Synchronized fun beginTick(nowElapsed: Long): Long? {
        if (current.schedulerClosed || current.taskRunning) return null
        current = current.copy(
            taskRunning = true,
            lastTickStartedElapsed = nowElapsed,
            taskTimedOut = false
        )
        return current.schedulerGeneration
    }

    @Synchronized fun finishTick(generation: Long, nowElapsed: Long) {
        if (!current.taskRunning) return
        // An old scheduler may finish after a replacement has started. Release its
        // process-wide task lease, but never let it mark the new scheduler healthy.
        if (current.schedulerClosed || current.schedulerGeneration != generation) {
            current = current.copy(
                taskRunning = false,
                taskTimedOut = false,
                lastTickEndedElapsed = nowElapsed
            )
            return
        }
        current = current.copy(
            schedulerState = BackgroundSchedulerState.HEALTHY,
            lastTickElapsed = nowElapsed,
            lastTickEndedElapsed = nowElapsed,
            lastSuccessfulTaskElapsed = nowElapsed,
            taskRunning = false,
            taskTimedOut = false,
            lastTaskErrorCode = null,
            lastTaskErrorElapsed = 0L,
            lastErrorCode = null,
            lastErrorDetail = null,
            lastErrorElapsed = 0L,
            // A completed critical tick, not merely startForeground(), proves recovery.
            recoveryAttemptCount = 0,
            lastRecoveryElapsed = 0L
        )
    }

    @Synchronized fun failTick(generation: Long, code: String, detail: String,
        nowElapsed: Long) {
        if (!current.taskRunning) return
        if (current.schedulerClosed || current.schedulerGeneration != generation) {
            current = current.copy(
                taskRunning = false,
                taskTimedOut = false,
                lastTickEndedElapsed = nowElapsed,
                lastTaskErrorCode = code,
                lastTaskErrorElapsed = nowElapsed,
                lastErrorCode = code,
                lastErrorDetail = "旧调度器任务失败：$detail",
                lastErrorElapsed = nowElapsed
            )
            return
        }
        current = current.copy(
            schedulerState = BackgroundSchedulerState.TASK_FAILED,
            lastTickElapsed = nowElapsed,
            lastTickEndedElapsed = nowElapsed,
            taskRunning = false,
            taskTimedOut = false,
            lastTaskErrorCode = code,
            lastTaskErrorElapsed = nowElapsed,
            lastErrorCode = code,
            lastErrorDetail = detail,
            lastErrorElapsed = nowElapsed
        )
    }

    @Synchronized fun abandonTick(generation: Long, nowElapsed: Long) {
        if (!current.taskRunning) return
        if (!current.schedulerClosed && current.schedulerGeneration == generation) {
            current = current.copy(
                schedulerState = BackgroundSchedulerState.TASK_FAILED,
                lastTickEndedElapsed = nowElapsed,
                taskRunning = false,
                taskTimedOut = false,
                lastTaskErrorCode = "BACKGROUND_TASK_ABORTED",
                lastTaskErrorElapsed = nowElapsed,
                lastErrorCode = "BACKGROUND_TASK_ABORTED",
                lastErrorDetail = "后台关键任务异常终止",
                lastErrorElapsed = nowElapsed
            )
        } else {
            current = current.copy(
                taskRunning = false,
                taskTimedOut = false,
                lastTickEndedElapsed = nowElapsed
            )
        }
    }

    @Synchronized fun markSchedulerStopped() {
        current = current.copy(
            schedulerState = BackgroundSchedulerState.NOT_STARTED,
            schedulerGeneration = current.schedulerGeneration + 1L,
            schedulerClosed = true,
            // Do not pretend a task was cancelled just because its executor was closed.
            // Its finally block must release the lease after it actually returns.
        )
    }

    @Synchronized fun setTcpListenerState(value: BackgroundTcpListenerState) {
        current = current.copy(tcpListenerState = value)
    }

    @Synchronized fun setNetworkState(value: BackgroundNetworkState) {
        current = current.copy(networkState = value)
    }

    @Synchronized fun beginRecoveryAttempt(
        consent: BackgroundConsent,
        healthy: Boolean,
        nowElapsed: Long
    ): BackgroundRecoveryDecision {
        val decision = BackgroundRecoveryPolicy.evaluate(
            consent, healthy, current.recoveryAttemptCount,
            current.lastRecoveryElapsed, nowElapsed
        )
        if (decision.allowed) {
            current = current.copy(
                recoveryAttemptCount = current.recoveryAttemptCount + 1,
                lastRecoveryElapsed = nowElapsed,
                serviceState = BackgroundServiceState.RESTART_REQUESTED,
                serviceStateElapsed = nowElapsed
            )
        }
        return decision
    }

    @Synchronized fun resetRecoveryAttempts() {
        current = current.copy(recoveryAttemptCount = 0, lastRecoveryElapsed = 0L)
    }

    @Synchronized fun recordRecoveryTrigger(trigger: BackgroundRecoveryTrigger) {
        current = current.copy(lastRecoveryTrigger = trigger)
    }
}

internal val BackgroundStartupStage.displayLabel: String
    get() = when (this) {
        BackgroundStartupStage.NONE -> "无"
        BackgroundStartupStage.PERMISSION_CHECK -> "权限检查"
        BackgroundStartupStage.NOTIFICATION_CREATE -> "通知创建"
        BackgroundStartupStage.FOREGROUND_START -> "前台服务启动"
        BackgroundStartupStage.MANAGER_INITIALIZATION -> "共享管理器初始化"
        BackgroundStartupStage.TCP_LISTENER_BIND -> "TCP 监听绑定"
        BackgroundStartupStage.SCHEDULER_START -> "后台调度器启动"
    }

internal val BackgroundServiceState.displayLabel: String
    get() = when (this) {
        BackgroundServiceState.STOPPED -> "已停止"
        BackgroundServiceState.START_REQUESTED -> "已请求启动"
        BackgroundServiceState.STARTING -> "正在初始化"
        BackgroundServiceState.RUNNING -> "正在运行"
        BackgroundServiceState.RESTART_REQUESTED -> "已请求恢复"
        BackgroundServiceState.START_FAILED -> "启动失败"
        BackgroundServiceState.STOPPED_BY_USER -> "用户已关闭"
        BackgroundServiceState.BLOCKED_BY_SYSTEM -> "系统阻止启动"
    }

internal val BackgroundSchedulerState.displayLabel: String
    get() = when (this) {
        BackgroundSchedulerState.NOT_STARTED -> "未启动"
        BackgroundSchedulerState.HEALTHY -> "正常执行"
        BackgroundSchedulerState.DELAYED -> "执行延迟"
        BackgroundSchedulerState.STALLED -> "调度停滞或超时"
        BackgroundSchedulerState.TASK_FAILED -> "关键任务失败"
    }

internal val BackgroundTcpListenerState.displayLabel: String
    get() = when (this) {
        BackgroundTcpListenerState.NOT_STARTED -> "未启动"
        BackgroundTcpListenerState.BINDING -> "正在绑定"
        BackgroundTcpListenerState.LISTENING -> "本机监听有效"
        BackgroundTcpListenerState.FAILED -> "绑定失败"
        BackgroundTcpListenerState.CLOSED_UNEXPECTEDLY -> "监听意外关闭"
    }

internal val BackgroundNetworkState.displayLabel: String
    get() = when (this) {
        BackgroundNetworkState.NO_NETWORK -> "系统未报告可用网络"
        BackgroundNetworkState.NETWORK_AVAILABLE -> "系统报告网络可用"
        BackgroundNetworkState.NETWORK_CHANGED -> "网络正在变化"
        BackgroundNetworkState.UNKNOWN -> "网络状态未知"
    }

internal val BackgroundRecoveryTrigger.displayLabel: String
    get() = when (this) {
        BackgroundRecoveryTrigger.NONE -> "暂无"
        BackgroundRecoveryTrigger.USER_REQUEST -> "用户主动开启"
        BackgroundRecoveryTrigger.APP_FOREGROUND -> "用户打开应用后检查"
        BackgroundRecoveryTrigger.BOOT_COMPLETED -> "设备启动广播"
        BackgroundRecoveryTrigger.PACKAGE_REPLACED -> "应用升级广播"
        BackgroundRecoveryTrigger.SYSTEM_STICKY_RESTART -> "Android START_STICKY 恢复"
    }

internal val BackgroundProcessExitSnapshot.displayLabel: String
    get() = when (kind) {
        BackgroundProcessExitKind.NOT_AVAILABLE -> "Android 10 或系统未提供退出记录"
        BackgroundProcessExitKind.SYSTEM_LOW_MEMORY -> "系统因内存压力回收进程"
        BackgroundProcessExitKind.CRASH -> "进程崩溃或初始化失败"
        BackgroundProcessExitKind.ANR -> "进程无响应（ANR）"
        BackgroundProcessExitKind.USER_REQUESTED ->
            "用户请求结束进程（可能是强行停止或从最近任务移除）"
        BackgroundProcessExitKind.USER_STOPPED -> "当前 Android 用户停止了应用"
        BackgroundProcessExitKind.PACKAGE_UPDATED -> "应用升级导致旧进程退出"
        BackgroundProcessExitKind.OTHER -> "系统以其他原因结束进程"
        BackgroundProcessExitKind.CHECK_FAILED -> "读取上次进程退出原因失败"
    }
