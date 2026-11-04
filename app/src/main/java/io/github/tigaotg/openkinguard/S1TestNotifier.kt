package io.github.tigaotg.openkinguard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Notifications are explicitly labeled exercises; they never claim a real rescue call. */
internal object S1TestNotifier {
    private const val CHANNEL_ID = "okg_s1_exercise_alert"
    // The old "okg_s1_checkin" channel was IMPORTANCE_DEFAULT. Android channels
    // are immutable after creation; use a new ID so upgrades gain heads-up defaults.
    private const val CHECKIN_CHANNEL_ID = "okg_s1_checkin_prominent_v2"
    private const val CLAIM_CHANNEL_ID = "okg_s1_claim_prominent_v1"
    private const val LAN_LOST_CHANNEL_ID = "okg_s2_lan_lost_v1"
    private const val SAFETY_ZONE_CHANNEL_ID = "okg_s3_safety_zone_v1"
    private const val HEART_PILOT_CHANNEL_ID = "okg_s6_heart_pilot_local_v1"

    private fun baseStatus(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return "系统通知权限尚未允许（Android 13 及以上须单独授权）"
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!mgr.areNotificationsEnabled()) return "系统已关闭本应用的通知"
        return "通知权限正常；仍受系统免打扰及通知样式设置影响"
    }

    fun status(context: Context): String {
        val base = baseStatus(context)
        if (!base.startsWith("通知权限正常")) return base
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && mgr.getNotificationChannel(CHANNEL_ID)?.importance ==
            NotificationManager.IMPORTANCE_NONE) return "演练提醒通知类别已被关闭"
        return base
    }

    fun checkinStatus(context: Context): String {
        val main = baseStatus(context)
        if (!main.startsWith("通知权限正常")) return main
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = mgr.getNotificationChannel(CHECKIN_CHANNEL_ID)
            ?: return "报平安悬浮提醒：将在下一条消息时启用新的高优先级通道"
        return if (channel.importance < NotificationManager.IMPORTANCE_HIGH)
            "报平安通道未开启高优先级，请在系统通知设置中允许横幅/浮动提醒"
        else "报平安已采用高优先级；悬浮显示还受系统通知设置与免打扰影响"
    }

    /** Empty return means Android NotificationManager accepted the request, not guaranteed display. */
    fun show(context: Context, text: String): String = send(context, text,
        CHANNEL_ID, "OpenKinGuard 演练提示", NotificationManager.IMPORTANCE_HIGH)

    fun showIncomingSos(context: Context, fingerprint: String, text: String): String =
        send(context, text, CHANNEL_ID, "OpenKinGuard 演练提示",
            NotificationManager.IMPORTANCE_HIGH, "sos:${fingerprint.take(64)}")

    fun showCheckin(context: Context, text: String, fingerprint: String? = null): String =
        send(context, text, CHECKIN_CHANNEL_ID, "报平安 · 显著提醒",
            NotificationManager.IMPORTANCE_HIGH,
            fingerprint?.let { "checkin:${it.take(64)}" })

    /** A new, high-priority channel so older immutable OS channel settings are not reused. */
    fun showClaim(context: Context, subjectFingerprint: String, text: String): String =
        send(context, text, CLAIM_CHANNEL_ID, "我的求助已有人接手",
            NotificationManager.IMPORTANCE_HIGH, "claim:${subjectFingerprint.take(64)}")

    fun clearIncomingSos(context: Context, senderFingerprint: String) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel("sos:${senderFingerprint.take(64)}", 1)
    }

    fun clearClaim(context: Context, subjectFingerprint: String) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel("claim:${subjectFingerprint.take(64)}", 1)
    }

    fun showLanLost(context: Context, fingerprint: String, nickname: String, stage: Int,
                    minutes: Int = if (stage >= 2) 10 else 3): String =
        send(context,
            "$nickname 超过${minutes}分钟未通过设备签名心跳；请检查连接（不代表人员遇险）",
            LAN_LOST_CHANNEL_ID, "局域网失联演练提醒",
            NotificationManager.IMPORTANCE_HIGH, "lost:${fingerprint.take(64)}")

    fun clearLanLost(context: Context, fingerprint: String) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel("lost:${fingerprint.take(64)}", 1)
    }

    /** Foreground-only exercise finding from two manually requested, signed locations. */
    fun showSafetyZone(context: Context, fingerprint: String, text: String): String =
        send(context, text, SAFETY_ZONE_CHANNEL_ID, "安全区域 · 定位演练提醒",
            NotificationManager.IMPORTANCE_HIGH, "zone:${fingerprint.take(64)}")

    /** Local-only, explicitly opted-in measurement experiment; never sends SOS or SMS. */
    fun showHeartPilot(context: Context, text: String): String =
        send(context, text, HEART_PILOT_CHANNEL_ID, "本机心率阈值试验",
            NotificationManager.IMPORTANCE_HIGH, "heart-pilot-local")

    fun heartPilotNotificationStatus(context: Context): String {
        val base = baseStatus(context)
        if (!base.startsWith("通知权限正常")) return base
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val importance = mgr.getNotificationChannel(HEART_PILOT_CHANNEL_ID)?.importance
        return when {
            importance == NotificationManager.IMPORTANCE_NONE -> "本机心率试验通知类别已被用户关闭"
            importance != null && importance < NotificationManager.IMPORTANCE_HIGH ->
                "本机心率通知类别没有高优先级，可能不会悬浮"
            else -> base
        }
    }

    private fun send(context: Context, text: String, channel: String, channelLabel: String,
                     importance: Int, stableTag: String? = null): String {
        // Do not let disabling the SOS rehearsal channel suppress unrelated
        // check-in, location, or local heart-rate channels.
        val state = baseStatus(context)
        if (!state.startsWith("通知权限正常")) return state
        return runCatching {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE)
            return@runCatching "该类通知已被用户关闭"
        if (Build.VERSION.SDK_INT >= 26) mgr.createNotificationChannel(NotificationChannel(
            channel, channelLabel, importance)
            .apply {
                description = "本机健康试验或已认证设备的演练消息，不是专业医疗/紧急救援提醒"
                enableVibration(true)
            })
        val pending = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(when (channel) {
                CHANNEL_ID -> "OpenKinGuard · SOS演练"
                CLAIM_CHANNEL_ID -> "OpenKinGuard · 已有人接手"
                LAN_LOST_CHANNEL_ID -> "OpenKinGuard · 局域网演练失联"
                SAFETY_ZONE_CHANNEL_ID -> "OpenKinGuard · 安全区域演练"
                HEART_PILOT_CHANNEL_ID -> "OpenKinGuard · 本机心率试验"
                else -> "OpenKinGuard · 报平安"
            })
            .setContentText(text)
            .setCategory(if (channel == CHECKIN_CHANNEL_ID) Notification.CATEGORY_MESSAGE
                else Notification.CATEGORY_EVENT)
            .setContentIntent(pending)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPriority(if (importance == NotificationManager.IMPORTANCE_HIGH)
                Notification.PRIORITY_HIGH else Notification.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        if (stableTag != null) mgr.notify(stableTag, 1, notification)
        else mgr.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        ""
        }.getOrElse { "通知提交失败：${it.javaClass.simpleName} ${it.message ?: ""}" }
    }
}
