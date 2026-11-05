package io.github.tigaotg.openkinguard

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** User consent is deliberately independent from whether Android currently hosts the service. */
internal enum class BackgroundConsent { NOT_DECIDED, ENABLED, USER_DISABLED }

internal object BackgroundConsentStore {
    private const val FILE = "okg.background.monitor.v1"
    private const val KEY = "user_choice"

    fun get(context: Context): BackgroundConsent = when (context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)) {
        "enabled" -> BackgroundConsent.ENABLED
        "disabled" -> BackgroundConsent.USER_DISABLED
        else -> BackgroundConsent.NOT_DECIDED
    }

    fun set(context: Context, allowed: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, if (allowed) "enabled" else "disabled").apply()
    }
}

internal enum class BackgroundPhase {
    RUNNING, RECOVERING, FAILED, USER_DISABLED, NOT_ENABLED, SYSTEM_RESTRICTED
}

/** Pure decision logic: an opt-out is never silently overridden. */
internal object BackgroundMonitorPolicy {
    fun mayAutoRecover(consent: BackgroundConsent, healthy: Boolean): Boolean =
        consent == BackgroundConsent.ENABLED && !healthy

    fun phase(consent: BackgroundConsent, serviceHealthy: Boolean, recovering: Boolean,
        systemRestricted: Boolean): BackgroundPhase = when {
        consent == BackgroundConsent.USER_DISABLED -> BackgroundPhase.USER_DISABLED
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
    val bluetoothRelevant: Boolean,
    val bluetoothGranted: Boolean,
    val medicineAlarmRelevant: Boolean,
    val exactAlarmAllowed: Boolean,
    val serviceDetail: String
) {
    val statusLabel: String get() = when (phase) {
        BackgroundPhase.RUNNING -> "正常运行：监听服务正在执行任务"
        BackgroundPhase.RECOVERING -> "正在恢复：系统正在启动后台监听"
        BackgroundPhase.FAILED -> "恢复失败：监听服务未能持续运行"
        BackgroundPhase.USER_DISABLED -> "用户已关闭：不会自动重新开启"
        BackgroundPhase.NOT_ENABLED -> "尚未开启：需先由本人同意"
        BackgroundPhase.SYSTEM_RESTRICTED -> "系统限制：后台运行受到系统限制"
    }

    val needsAttention: Boolean get() = phase != BackgroundPhase.RUNNING ||
        !notificationsAllowed || backgroundRestricted ||
        (locationRelevant && !locationGranted) ||
        (bluetoothRelevant && !bluetoothGranted)

    fun advice(): List<String> = buildList {
        if (phase != BackgroundPhase.RUNNING) add("后台监听：$statusLabel")
        if (!notificationsAllowed) add("通知已关闭：可能看不到收到的 SOS、报平安等提醒")
        if (backgroundRestricted) add("系统限制后台执行：请查看应用的后台使用设置")
        if (locationRelevant && !locationGranted) add("位置权限不足：已授权家人的位置请求可能无法完成")
        if (bluetoothRelevant && !bluetoothGranted) add("附近设备权限不足：穿戴心率读取可能失败")
        if (medicineAlarmRelevant && !exactAlarmAllowed)
            add("精确闹钟未获允许：服药提醒将使用可能延迟的普通闹钟")
    }
}

internal object BackgroundStartupCheck {
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

    fun inspect(context: Context, recovering: Boolean = false): BackgroundReadiness {
        val app = context.applicationContext
        val notifications = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val allowedNotification = notifications.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED)
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val restricted = manager.isBackgroundRestricted
        val battery = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryExempt = battery.isIgnoringBatteryOptimizations(app.packageName)
        val locationRelevant = runCatching { S1Runtime.manager(app).snapshot().history
            .locationSharingRelations.isNotEmpty() }.getOrDefault(false)
        val locationGranted = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
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
        val consent = BackgroundConsentStore.get(app)
        val probe = S1LanService.runtimeProbe()
        val phase = BackgroundMonitorPolicy.phase(consent, probe.healthy,
            recovering || probe.starting, restricted)
        return BackgroundReadiness(consent, phase, allowedNotification, batteryExempt,
            restricted, locationRelevant, locationGranted, bluetoothRelevant,
            bluetoothGranted, medicineRelevant, exactAlarm,
            probe.failure.orEmpty().ifBlank {
                if (probe.healthy) "服务心跳正常；厂商自启动限制仍需人工检查"
                else "尚未收到服务的有效运行心跳"
            })
    }
}
