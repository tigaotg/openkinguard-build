package io.github.tigaotg.openkinguard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

internal object ConnectionStatusPolicy {
    fun isReachable(lan: Boolean, relay: Boolean, direct: Boolean): Boolean =
        lan || relay || direct

    fun reachabilityLabel(lan: Boolean, relay: Boolean, direct: Boolean): String =
        if (isReachable(lan, relay, direct)) "已通过至少一条路径完成设备验签"
        else "暂未确认可达（不代表手机关机）"
}

internal data class ConnectionStatusUiState(
    val updatedAtMillis: Long,
    val networkSummary: String,
    val appForeground: Boolean,
    val lanListening: Boolean,
    val tcpListenerStatus: String,
    val backgroundListening: Boolean,
    val backgroundStatus: String,
    val discoveredNodeCount: Int,
    val authenticatedLanRelationCount: Int,
    val discoveryError: String,
    val privateRelayEnabled: Boolean,
    val privateRelays: List<PrivateRelayNodeDiagnostic>,
    val publicSignalEnabled: Boolean,
    val publicSignalConnected: Boolean,
    val publicSignalStatus: String,
    val directEnabled: Boolean,
    val directActive: Boolean,
    val directStatus: String,
    val peers: List<PeerConnectionDiagnostic>
) {
    fun toReport(): String = buildString {
        appendLine("OpenKinGuard 连接诊断")
        appendLine("状态读取时间：${if (updatedAtMillis > 0L) DateFormat.getDateTimeInstance().format(Date(updatedAtMillis)) else "尚未主动检测"}")
        appendLine("系统网络：$networkSummary")
        appendLine("APP：${if (appForeground) "前台" else "后台"}")
        appendLine("局域网服务：${if (lanListening) "监听中" else "未监听"}；TCP：$tcpListenerStatus")
        appendLine("后台持续监听：${if (backgroundListening) "开启" else "未开启"}；$backgroundStatus")
        appendLine("局域网发现：$discoveredNodeCount 台；验签关系 $authenticatedLanRelationCount 条")
        appendLine("家庭 WSS 中继：${if (privateRelayEnabled) "已启用" else "未启用"}")
        privateRelays.forEach { node ->
            appendLine("  节点 ${node.index}：Socket=${node.socketConnected}；身份验签=${node.identityAuthenticated}；" +
                "家庭能力=${node.familyPairingSupported}；离线密文暂存=${node.offlineSpoolSupported}；" +
                "最近通信=${ageLabel(node.lastCommunicationAtMillis, updatedAtMillis)}")
        }
        appendLine("公共 Nostr 信令：${if (publicSignalEnabled) "已启用" else "未启用"}；" +
            "${if (publicSignalConnected) "已连接" else "未确认连接"}")
        appendLine("UDP P2P：${if (directEnabled) "已启用" else "未启用"}；" +
            "${if (directActive) "本机 Socket 活跃" else "本机 Socket 未活跃"}")
        appendLine("家人设备：")
        peers.forEach { peer ->
            appendLine("  ${peer.displayName}（${peer.roleDescription}）：${ConnectionStatusPolicy.reachabilityLabel(
                peer.lanVerified, peer.relayVerified, peer.directVerified)}")
            appendLine("    LAN=${peer.lanVerified}；WSS=${peer.relayVerified}；UDP=${peer.directVerified}")
            appendLine("    最近收到签名消息：${ageLabel(peer.lastSignedMessageAtMillis, updatedAtMillis)}")
        }
        appendLine("说明：未确认可达不等于设备关机；可能是网络切换、系统后台限制、NAT 或暂未完成验签。")
        appendLine("安全提示：中继连接或暂存成功不等于接收手机已收到；以设备签名回执为准。")
    }
}

internal object ConnectionStatusNetwork {
    fun summary(context: Context): String {
        return runCatching {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
            val network = connectivity.activeNetwork
            if (network == null) "无默认网络" else {
                val capabilities = connectivity.getNetworkCapabilities(network)
                if (capabilities == null) "默认网络存在，但暂时无法读取网络能力" else {
                    val transports = buildList {
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("移动数据")
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("以太网")
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("蓝牙网络")
                    }.joinToString("+").ifBlank { "其他网络" }
                    val internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    "$transports；互联网能力${if (internet) "存在" else "不可用"}；" +
                        "系统验证${if (validated) "通过" else "未通过/尚未完成"}"
                }
            }
        }.getOrElse { "网络状态读取失败：${it.javaClass.simpleName}" }
    }
}

internal data class PeerConnectionDiagnostic(
    val relationId: String,
    val displayName: String,
    val roleDescription: String,
    val lanVerified: Boolean,
    val relayVerified: Boolean,
    val directVerified: Boolean,
    val lastSignedMessageAtMillis: Long
) {
    val verifiedReachable: Boolean
        get() = ConnectionStatusPolicy.isReachable(lanVerified, relayVerified, directVerified)
}

@Composable
internal fun ConnectionStatusSection(
    state: ConnectionStatusUiState,
    refreshing: Boolean,
    feedback: String,
    onRefresh: () -> Unit,
    onCopy: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("本页面可随时重新打开。状态数据约每5秒刷新一次，已绑定设备约每30秒进行一次签名可达性检查。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh, enabled = !refreshing) {
                Text(if (refreshing) "正在检测…" else "立即检测")
            }
            OutlinedButton(onClick = onCopy) { Text("复制诊断摘要") }
        }
        if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodySmall)
        Text(if (state.updatedAtMillis > 0L)
            "最近主动检测：${DateFormat.getDateTimeInstance().format(Date(state.updatedAtMillis))}"
            else "尚未完成主动检测", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        StatusCard("① 本机状态") {
            StatusLine("系统网络", state.networkSummary)
            StatusLine("APP运行位置", if (state.appForeground) "正在前台运行" else "当前在后台")
            StatusLine("局域网服务", if (state.lanListening) "监听中" else "未监听")
            StatusLine("TCP监听器", state.tcpListenerStatus)
            StatusLine("后台持续监听", if (state.backgroundListening) "已开启" else "未开启")
            Text(state.backgroundStatus, style = MaterialTheme.typography.bodySmall)
            StatusLine("局域网发现", "发现 ${state.discoveredNodeCount} 台设备；${state.authenticatedLanRelationCount} 条关系已完成验签")
            if (state.discoveryError.isNotBlank())
                Text("发现错误：${state.discoveryError}", color = MaterialTheme.colorScheme.error)
        }

        StatusCard("② 家庭私人 WSS 中继") {
            StatusLine("配置状态", if (state.privateRelayEnabled) "已启用" else "未启用")
            if (state.privateRelays.isEmpty()) {
                Text(if (state.privateRelayEnabled) "尚无已配置的中继连接。" else "未启用家庭私人中继。",
                    style = MaterialTheme.typography.bodySmall)
            }
            state.privateRelays.forEach { node ->
                HorizontalDivider()
                Text("中继节点 ${node.index}", style = MaterialTheme.typography.titleSmall)
                StatusLine("WebSocket", if (node.socketConnected) "已连接" else "未连接")
                StatusLine("本机身份认证", if (node.identityAuthenticated) "已验证" else "未验证")
                StatusLine("家庭设备发现", if (node.familyPairingSupported) "支持" else "未确认")
                StatusLine("离线密文暂存", when {
                    node.offlineSpoolSupported -> "节点已通告支持"
                    node.identityAuthenticated -> "未通告（可能是旧版中继）"
                    else -> "尚未验证节点能力"
                })
                StatusLine("最近通信", ageLabel(node.lastCommunicationAtMillis, state.updatedAtMillis))
                if (node.status.isNotBlank())
                    Text("状态：${node.status}", style = MaterialTheme.typography.bodySmall)
                node.lastFailureReason?.takeIf { it.isNotBlank() }?.let {
                    Text("最近失败：${it.take(120)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            Text("注意：中继在线只代表本机与中继完成连接/身份验证，不代表另一台手机在线。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        StatusCard("③ 公共信令与 P2P") {
            StatusLine("公共 Nostr 信令", when {
                !state.publicSignalEnabled -> "未启用"
                state.publicSignalConnected -> "已连接"
                else -> "未确认连接"
            })
            Text(state.publicSignalStatus, style = MaterialTheme.typography.bodySmall)
            Text("Nostr 只交换连接地址和信令，不承载 SOS 正文。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            StatusLine("UDP P2P 本机监听", when {
                !state.directEnabled -> "未启用"
                state.directActive -> "已启用且 Socket 活跃"
                else -> "已请求但 Socket 未活跃"
            })
            Text(state.directStatus, style = MaterialTheme.typography.bodySmall)
        }

        StatusCard("④ 已绑定家人手机") {
            if (state.peers.isEmpty()) {
                Text("尚未建立帮助关系。请先进入“连接家人”完成绑定。")
            }
            state.peers.forEach { peer ->
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(peer.displayName, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    Text(if (peer.verifiedReachable) "已验证可达" else "未确认可达",
                        color = if (peer.verifiedReachable) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge)
                }
                Text(peer.roleDescription, style = MaterialTheme.typography.bodySmall)
                StatusLine("局域网 TCP", if (peer.lanVerified) "近期验签成功" else "未验证")
                StatusLine("私人 WSS 中继", if (peer.relayVerified) "近120秒内验签成功" else "近120秒内无验签成功")
                StatusLine("UDP P2P 直连", if (peer.directVerified) "近120秒内验签成功" else "近120秒内无验签成功")
                StatusLine("最近收到对方签名消息",
                    ageLabel(peer.lastSignedMessageAtMillis, state.updatedAtMillis))
            }
            Text("“未确认可达”不等于手机关机。可能是对方网络切换、系统后台限制、运营商 NAT、" +
                "应用被停止，或尚未完成新一轮签名握手。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("状态说明：中继已连接、消息已暂存、手机已收到、家属已确认是四种不同状态；" +
            "只有对应手机的有效签名回执才能证明设备收到消息。",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.9f))
        Text(value, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1.1f))
    }
}

private fun ageLabel(timestamp: Long, now: Long): String = when {
    timestamp <= 0L -> "暂无记录"
    now <= 0L || now < timestamp -> "时间待校准"
    now - timestamp < 60_000L -> "${(now - timestamp) / 1_000L} 秒前"
    now - timestamp < 3_600_000L -> "${(now - timestamp) / 60_000L} 分钟前"
    else -> DateFormat.getDateTimeInstance().format(Date(timestamp))
}
