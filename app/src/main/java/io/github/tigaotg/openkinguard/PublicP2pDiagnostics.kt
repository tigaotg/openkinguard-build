package io.github.tigaotg.openkinguard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address
import java.text.DateFormat
import java.util.Date

/** User-triggered diagnostic only: no location, contact numbers, secrets or background polling. */
internal object PublicP2pDiagnostics {
    data class Advice(val title: String, val detail: String)

    fun explain(enabled: Boolean, signalReady: Boolean, directActive: Boolean,
                directStatus: String, verifiedDirectCount: Int = 0): Advice {
        // A verified UDP route can remain usable briefly while Nostr reconnects.
        if (verifiedDirectCount > 0)
            return Advice("已验签直连 $verifiedDirectCount 台设备",
                "P2P 通道可用；单条 SOS 仍须等待接收方签名回执。")
        if (!enabled) return Advice("公共信令未开启", "先打开公共信令，让两部手机都连到至少一个相同的节点。")
        if (!signalReady) return Advice("公共节点还未确认", "若等待较久，请展开技术信息检查节点拒收或网络超时。")
        if (!directActive) {
            if (directStatus.contains("等待系统默认网络"))
                return Advice("等待系统网络恢复", directStatus)
            if (directStatus.contains("Socket尚未建立"))
                return Advice("本机 UDP 监听尚未就绪", directStatus)
            return Advice("信令已连接，尚未开始直连", "还需要两部手机都点击开始 P2P 测试。")
        }
        val local = Regex("本机候选(\\d+)个")
            .find(directStatus)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val remote = Regex("对端候选(\\d+)个")
            .find(directStatus)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        if (local == 0) return Advice("正在获取本机连接地址", "稍等一会；持续为零可生成完整诊断查看 STUN 结果。")
        if (remote == 0) return Advice("尚未收到家人手机的连接地址", "检查双方是否使用同一公共节点并都开启了 P2P。")
        return Advice("已交换地址，仍在尝试直连", "收到了 $remote 个对方候选，但没有验签回执；可能是运营商 NAT 或防火墙阻止。")
    }

    /** Hide mapped IP and port while keeping server reachability, RTT and NAT consistency. */
    fun safeStunSummary(raw: String): String = raw.replace(
        Regex("→(?:\\d{1,3}\\.){3}\\d{1,3}:\\d{1,5}"),
        "→[公网地址和端口已隐藏]")

    fun build(context: Context, enabled: Boolean, signalReady: Boolean,
              directActive: Boolean, directStatus: String, nodeStatus: String,
              relationCount: Int, verifiedDirectCount: Int, stunResult: String,
              includePublicIp: Boolean): String {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capability = cm.getNetworkCapabilities(network)
        val links = cm.getLinkProperties(network)
        val networkType = when {
            capability?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            capability?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "移动数据"
            capability?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "以太网"
            else -> "未识别到活跃网络"
        }
        val addrs = links?.linkAddresses.orEmpty().map { it.address }
        val ipv4 = addrs.any { it is Inet4Address && !it.isLoopbackAddress }
        val ipv6 = addrs.any { it is Inet6Address && !it.isLinkLocalAddress }
        val advice = explain(enabled, signalReady, directActive, directStatus, verifiedDirectCount)
        return listOf(
            "OpenKinGuard · 公共节点和 P2P 诊断（用户手动生成）",
            "生成时间：${DateFormat.getDateTimeInstance().format(Date())}",
            "手机型号：${Build.MANUFACTURER} ${Build.MODEL}；Android API ${Build.VERSION.SDK_INT}",
            "网络：$networkType；IPv4：${if (ipv4) "有" else "无"}；IPv6：${if (ipv6) "有" else "无"}",
            "系统互联网验证：${if (capability?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) "已通过" else "尚未确认"}",
            "已绑定帮助关系：$relationCount 条（不包含姓名和号码）",
            "公共信令：${if (enabled) "已启用" else "已关闭"}；节点确认：${if (signalReady) "至少一个已确认" else "尚未确认"}",
            "P2P UDP：${if (directActive) "已开启" else "未开启"}；最近验签直连设备：$verifiedDirectCount 条",
            "初步判断：${advice.title}；${advice.detail}",
            "--- 公共节点连接、订阅、公告和拒绝原因 ---",
            nodeStatus,
            "--- P2P 本地 UDP 端口、双方候选与握手错误 ---",
            directStatus,
            "--- 本次 STUN / NAT 探测 ---",
            if (includePublicIp) stunResult else safeStunSummary(stunResult),
            "注意：本次独立 STUN 使用临时端口，不能代替 P2P 持续 UDP 端口的可达性验证。",
            "安全：不包含私钥、完整指纹、GPS、联系人号码或病历；可选的公网 IP 属于敏感信息。",
            "判定：公共节点连通不等于 P2P 成功；P2P 成功仍不等于 SOS 已送达，必须看对应消息的签名回执。"
        ).joinToString("\n")
    }
}
