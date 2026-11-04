package io.github.tigaotg.openkinguard

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.text.DateFormat
import java.util.Date

/** Foreground, on-demand factual checks; never claims somebody has received an alert. */
internal object S4ReadinessDiagnostics {
    data class Battery(val percent: Int?, val charging: Boolean) {
        fun label(warning: Int, critical: Int): String = when {
            percent == null -> "电池读数不可用"
            charging -> "本机电量 $percent%，正在充电"
            percent <= critical -> "本机电量 $percent%，严重偏低（不自动通知他人）"
            percent <= warning -> "本机电量 $percent%，偏低（不自动通知他人）"
            else -> "本机电量 $percent%，正常"
        }
    }

    fun battery(context: Context): Battery {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Battery(null, false)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return Battery(if (level >= 0 && scale > 0) (100L * level / scale).toInt()
            .coerceIn(0, 100) else null,
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL)
    }

    fun evaluate(context: Context, state: ExerciseState, session: ExerciseUiState,
                 relationsCount: Int, backgroundListening: Boolean): String {
        val app = context.applicationContext
        val report = mutableListOf<String>()
        report += "OpenKinGuard 本机就绪自检 · ${DateFormat.getDateTimeInstance().format(Date())}"
        report += "这是设备诊断，不是紧急报警，也不会联系他人。"
        val memory = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        report += "运行环境：Android ${Build.VERSION.SDK_INT}，" +
            "当前可用内存 ${memory.availMem / (1024 * 1024)} MiB，" +
            (if (memory.lowMemory) "系统报告内存紧张" else "系统未报告内存紧张")

        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        val notificationAllowed = notificationManager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED)
        report += if (notificationAllowed) "✓ 通知：系统已允许（是否弹窗仍由通知渠道控制）"
            else "✕ 通知：未允许或被系统关闭，请到设置中开启"

        val fine = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        report += when {
            fine -> "✓ 定位权限：精确定位已允许"
            coarse -> "! 定位权限：仅模糊定位，不能证明 GPS 可用"
            else -> "✕ 定位权限：尚未授权"
        }
        val location = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsOn = runCatching { location.isProviderEnabled(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)
        val networkOn = runCatching { location.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
            .getOrDefault(false)
        report += "定位开关：GPS ${if (gpsOn) "已开" else "未开"}、网络定位 " +
            (if (networkOn) "已开；实际定位请另点‘检测本机 GPS’" else
                "未开；实际定位请另点‘检测本机 GPS’")

        val network = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = network.getNetworkCapabilities(network.activeNetwork)
        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        report += when {
            validated -> "✓ 网络：系统报告互联网连接已验证（不等于公共节点可用）"
            connected -> "! 网络：已建立网络，但未获系统互联网验证"
            else -> "✕ 网络：无已知互联网连接；局域网功能可能仍可用"
        }
        report += if (session.listening) "✓ 本机连接监听已启动" else "✕ 本机连接监听未启动"
        report += if (backgroundListening) "后台监听：用户已手动开启，仍可能被系统省电限制" else
            "后台监听：未开启，退出应用后可能无法接收消息"
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        report += if (power.isIgnoringBatteryOptimizations(app.packageName))
            "电池优化：应用已获系统豁免（仍不保证持续运行）" else
            "电池优化：系统可能限制后台活动，可在系统设置中自行调整"

        report += battery(app).label(state.batteryWarningPercent, state.batteryCriticalPercent)
        val count = (session.verifiedRelations + session.relayVerifiedRelations).size
        report += "已绑定 $relationsCount 条定向关系；目前已验签可通信 $count 条"
        report += "私人 WSS：${if (state.relayEnabled) "已启用" else "未启用"}；" +
            "公共 Nostr：${if (state.nostrRelayEnabled) "已启用" else "未启用"}"
        report += "中继连接：${if (session.relayConnected) "至少一个套接字在线" else "目前没有已连接套接字"}"
        report += "注意：套接字连接不代表另一台手机在线；只有设备验签心跳与消息签名回执才算确认。"
        report += "本机设定：失联 ${state.lossWarningMinutes} 分钟提醒、" +
            "${state.lossCriticalMinutes} 分钟严重提醒；电量 ${state.batteryWarningPercent}% / " +
            "${state.batteryCriticalPercent}% 提示"
        return report.joinToString("\n")
    }
}
