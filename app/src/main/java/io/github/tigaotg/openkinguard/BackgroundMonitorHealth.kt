package io.github.tigaotg.openkinguard

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** User consent is deliberately independent from whether Android currently hosts the service. */
internal enum class BackgroundConsent { NOT_DECIDED, ENABLED, USER_DISABLED, CONSENT_INVALID }

internal object BackgroundConsentStore {
    private const val FILE = "okg.background.monitor.v1"
    private const val KEY = "user_choice"

    internal fun parse(raw: String?): BackgroundConsent = when (raw) {
        null -> BackgroundConsent.NOT_DECIDED
        "enabled" -> BackgroundConsent.ENABLED
        "disabled" -> BackgroundConsent.USER_DISABLED
        else -> BackgroundConsent.CONSENT_INVALID
    }

    fun get(context: Context): BackgroundConsent = runCatching {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, null)
    }.fold(
        onSuccess = ::parse,
        // A wrong SharedPreferences value type or unreadable store must fail closed.
        onFailure = { BackgroundConsent.CONSENT_INVALID }
    )

    fun set(context: Context, allowed: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, if (allowed) "enabled" else "disabled").apply()
    }
}

internal enum class BackgroundPhase {
    RUNNING, RECOVERING, FAILED, USER_DISABLED, NOT_ENABLED, CONSENT_INVALID,
    SYSTEM_RESTRICTED
}

/** Pure decision logic: an opt-out or invalid consent is never silently overridden. */
internal object BackgroundMonitorPolicy {
    fun mayAutoRecover(consent: BackgroundConsent, healthy: Boolean): Boolean =
        consent == BackgroundConsent.ENABLED && !healthy

    fun phase(consent: BackgroundConsent, serviceHealthy: Boolean, recovering: Boolean,
        systemRestricted: Boolean): BackgroundPhase = when {
        consent == BackgroundConsent.USER_DISABLED -> BackgroundPhase.USER_DISABLED
        consent == BackgroundConsent.CONSENT_INVALID -> BackgroundPhase.CONSENT_INVALID
        consent == BackgroundConsent.NOT_DECIDED -> BackgroundPhase.NOT_ENABLED
        serviceHealthy -> BackgroundPhase.RUNNING
        recovering -> BackgroundPhase.RECOVERING
        systemRestricted -> BackgroundPhase.SYSTEM_RESTRICTED
        else -> BackgroundPhase.FAILED
    }
}

/** A fresh on-device snapshot; no requested permissions are granted by this check. */
internal data class BackgroundReadiness(
    val consent: BackgroundConsent,
    val phase: BackgroundPhase,
    val notificationsAllowed: Boolean,
    val batteryExempt: Boolean,
    val backgroundRestricted: Boolean,
    val locationRelevant: Boolean,
    val locationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val bluetoothRelevant: Boolean,
    val bluetoothGranted: Boolean,
    val medicineAlarmRelevant: Boolean,
    val exactAlarmAllowed: Boolean,
    val serviceDetail: String,
    val health: BackgroundHealthSnapshot = BackgroundHealthSnapshot(),
    val previousProcessExit: BackgroundProcessExitSnapshot = BackgroundProcessExitSnapshot()
) {
    val statusLabel: String get() = when (phase) {
        BackgroundPhase.RUNNING -> "本机后台服务正常（不代表家属在线）"
        BackgroundPhase.RECOVERING -> "正在尝试恢复本机后台服务"
        BackgroundPhase.FAILED -> "本机后台服务异常：请查看分层诊断"
        BackgroundPhase.USER_DISABLED -> "用户已关闭：不会自动重新开启"
        BackgroundPhase.NOT_ENABLED -> "尚未开启：需先由本人同意"
        BackgroundPhase.CONSENT_INVALID -> "授权记录异常：不会自动启动，请重新确认"
        BackgroundPhase.SYSTEM_RESTRICTED -> "系统限制：后台运行受到系统限制"
    }

    val needsAttention: Boolean get() = phase != BackgroundPhase.RUNNING ||
        health.networkState == BackgroundNetworkState.NO_NETWORK ||
        !notificationsAllowed || !batteryExempt || backgroundRestricted ||
        (locationRelevant && (!locationGranted || !backgroundLocationGranted)) ||
        (bluetoothRelevant && !bluetoothGranted) ||
        (medicineAlarmRelevant && !exactAlarmAllowed)

    fun advice(): List<String> = buildList {
        if (phase != BackgroundPhase.RUNNING) add("后台监听：$statusLabel")
        add("前台服务：${health.serviceState.displayLabel}")
        add("本机调度器：${health.schedulerState.displayLabel}")
        add("本机 TCP 监听：${health.tcpListenerState.displayLabel}")
        add("系统网络：${health.networkState.displayLabel}")
        add("远端家属可达性：本阶段不检测，不能由本机状态推断")
        health.lastErrorDetail?.takeIf { it.isNotBlank() }?.let { add("最近错误：$it") }
        if (!notificationsAllowed) add("通知已关闭：可能看不到收到的 SOS、报平安等提醒")
        if (!batteryExempt) add("电池优化仍开启：系统可能延迟或停止后台监听，请检查是否允许 OpenKinGuard 不受电池优化限制")
        if (backgroundRestricted) add("系统限制后台执行：请查看应用的后台使用设置")
        if (locationRelevant && !locationGranted) add("前台定位权限不足：已授权家人的位置请求可能无法完成")
        if (locationRelevant && !backgroundLocationGranted) add("后台定位权限未授予：App 不在前台时可能无法响应家人的位置请求")
        if (bluetoothRelevant && !bluetoothGranted) add("附近设备权限不足：穿戴心率读取可能失败")
        if (medicineAlarmRelevant && !exactAlarmAllowed)
            add("精确闹钟未获允许：服药提醒将使用可能延迟的普通闹钟")
    }
}

internal object BackgroundStartupCheck {
    private fun inspectPreviousProcessExit(
        app: Context,
        activityManager: ActivityManager
    ): BackgroundProcessExitSnapshot {
        if (Build.VERSION.SDK_INT < 30) return BackgroundProcessExitSnapshot()
        return runCatching {
            activityManager.getHistoricalProcessExitReasons(app.packageName, 0, 1)
                .firstOrNull()
        }.fold(
            onSuccess = { info ->
                if (info == null) return@fold BackgroundProcessExitSnapshot()
                val kind = when (info.reason) {
                    ApplicationExitInfo.REASON_LOW_MEMORY,
                    ApplicationExitInfo.REASON_MEMORY_LIMITER -> 
                        BackgroundProcessExitKind.SYSTEM_LOW_MEMORY
                    ApplicationExitInfo.REASON_CRASH,
                    ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ->
                        BackgroundProcessExitKind.CRASH
                    ApplicationExitInfo.REASON_ANR -> BackgroundProcessExitKind.ANR
                    ApplicationExitInfo.REASON_USER_REQUESTED ->
                        BackgroundProcessExitKind.USER_REQUESTED
                    ApplicationExitInfo.REASON_USER_STOPPED ->
                        BackgroundProcessExitKind.USER_STOPPED
                    else -> if (Build.VERSION.SDK_INT >= 34 &&
                        info.reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED) {
                        BackgroundProcessExitKind.PACKAGE_UPDATED
                    } else BackgroundProcessExitKind.OTHER
                }
                BackgroundProcessExitSnapshot(
                    kind = kind,
                    timestamp = info.timestamp,
                    detail = info.description
                )
            },
            onFailure = { error ->
                BackgroundProcessExitSnapshot(
                    kind = BackgroundProcessExitKind.CHECK_FAILED,
                    detail = error.javaClass.simpleName
                )
            }
        )
    }

    /** Standard settings only. OEM autostart controls may need manual navigation. */
    fun openBackgroundSettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.getOrElse {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openAppDetailsSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.getOrElse { openAppDetailsSettings(context) }
    }

    fun inspect(context: Context, recovering: Boolean = false): BackgroundReadiness {
        val app = context.applicationContext
        val notifications = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val allowedNotification = notifications.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED)
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val restricted = manager.isBackgroundRestricted
        val previousProcessExit = inspectPreviousProcessExit(app, manager)
        val battery = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryExempt = battery.isIgnoringBatteryOptimizations(app.packageName)
        val locationRelevant = runCatching { S1Runtime.manager(app).snapshot().history
            .locationSharingRelations.isNotEmpty() }.getOrDefault(false)
        val locationGranted = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val backgroundLocationGranted = Build.VERSION.SDK_INT < 29 ||
            app.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val bluetoothRelevant = app.getSharedPreferences("okg.health.heart_pilot.v1",
            Context.MODE_PRIVATE).getBoolean("enabled", false)
        val bluetoothGranted = if (Build.VERSION.SDK_INT >= 31) {
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else locationGranted
        val medicineRelevant = runCatching { MedicalVault(app).snapshot()
            .records(MedicalKind.MEDICINE).any { MedicalSchedule.active(it) &&
                MedicalSchedule.slots(it).isNotEmpty() } }.getOrDefault(false)
        val exactAlarm = Build.VERSION.SDK_INT < 31 ||
            (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

        // Read current system connectivity even when the foreground service is stopped.
        // This is only a local network-state observation, not an Internet or peer probe.
        val observedNetwork = runCatching {
            val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
            val active = connectivity.activeNetwork
            when {
                active == null -> BackgroundNetworkState.NO_NETWORK
                connectivity.getNetworkCapabilities(active) == null ->
                    BackgroundNetworkState.UNKNOWN
                else -> BackgroundNetworkState.NETWORK_AVAILABLE
            }
        }.getOrDefault(BackgroundNetworkState.UNKNOWN)
        BackgroundMonitorHealth.setNetworkState(observedNetwork)

        val consent = BackgroundConsentStore.get(app)
        val probe = S1LanService.runtimeProbe()
        val health = probe.health
        val phase = BackgroundMonitorPolicy.phase(consent, probe.healthy,
            recovering || probe.starting,
            restricted || health.serviceState == BackgroundServiceState.BLOCKED_BY_SYSTEM)
        return BackgroundReadiness(
            consent = consent,
            phase = phase,
            notificationsAllowed = allowedNotification,
            batteryExempt = batteryExempt,
            backgroundRestricted = restricted,
            locationRelevant = locationRelevant,
            locationGranted = locationGranted,
            backgroundLocationGranted = backgroundLocationGranted,
            bluetoothRelevant = bluetoothRelevant,
            bluetoothGranted = bluetoothGranted,
            medicineAlarmRelevant = medicineRelevant,
            exactAlarmAllowed = exactAlarm,
            serviceDetail = probe.failure.orEmpty().ifBlank {
                if (probe.healthy) "本机服务、调度器和 TCP 监听均正常；不代表家属在线"
                else "本机后台服务尚未达到完整健康条件"
            },
            health = health,
            previousProcessExit = previousProcessExit
        )
    }
}
