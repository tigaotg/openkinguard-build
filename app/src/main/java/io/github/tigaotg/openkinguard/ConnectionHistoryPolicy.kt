package io.github.tigaotg.openkinguard

import java.security.MessageDigest

/** Platform-neutral retention, deduplication and privacy rules for local 7-day history. */
internal object ConnectionHistoryPolicy {
    const val RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
    const val SNAPSHOT_INTERVAL_MS = 60_000L
    const val MONITOR_GAP_MS = 3 * 60_000L
    const val MAX_ROWS = 24_000
    const val MAX_STATE_ITEMS = 100
    const val MAX_DISPLAY_ROWS = 400
    private val safeKey = Regex("[a-z][a-z0-9/_-]{0,63}")
    private val safeValue = Regex("[a-z][a-z0-9_-]{0,31}")

    data class Transition(val path: String, val before: String, val after: String)

    fun isRetained(timestampMs: Long, nowMs: Long): Boolean =
        timestampMs in (nowMs - RETENTION_MS)..nowMs

    fun shouldSnapshot(lastSnapshotMs: Long, nowMs: Long): Boolean =
        lastSnapshotMs <= 0L || nowMs < lastSnapshotMs ||
            nowMs - lastSnapshotMs >= SNAPSHOT_INTERVAL_MS

    fun sanitizeState(state: Map<String, String>): Map<String, String> =
        state.asSequence()
            .filter { (key, value) -> safeKey.matches(key) && safeValue.matches(value) }
            .sortedBy { it.key }
            .take(MAX_STATE_ITEMS)
            .associate { it.key to it.value }

    fun transitions(previous: Map<String, String>, current: Map<String, String>): List<Transition> =
        (previous.keys + current.keys).toSortedSet().mapNotNull { key ->
            val old = previous[key] ?: "unknown"
            val new = current[key] ?: "removed"
            if (old == new) null else Transition(key, old, new)
        }

    fun peerAlias(relationId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(relationId.toByteArray(Charsets.US_ASCII))
        return digest.take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Every value is a fixed status code: raw exception messages must never be written. */
    fun relayPhase(status: String, authenticated: Boolean, socketConnected: Boolean): String = when {
        authenticated -> "ready"
        status.contains("路由器端未绑定") || status.contains("授权已取消") -> "denied"
        status.contains("没有默认网络") || status.contains("等待系统默认网络") -> "waiting_network"
        status.contains("正在重新连接") || status.contains("正在连接") || status.contains("连接中") -> "connecting"
        status.contains("重连") || status.contains("断开") || status.contains("关闭") -> "backoff"
        socketConnected -> "authenticating"
        status.contains("连接") || status.contains("挑战") -> "connecting"
        else -> "idle"
    }

    /** Raw diagnostics can include URLs, IPs and IDs; never persist them. */
    fun failureCategory(error: String?): String = when {
        error.isNullOrBlank() -> "none"
        Regex("(?i)(refus|denied|forbidden|4403|unauthor|拒绝|禁止|授权)").containsMatchIn(error) -> "denied"
        Regex("(?i)(cert|tls|ssl|x509|证书|指纹)").containsMatchIn(error) -> "tls"
        Regex("(?i)(dns|resolve|unknown host|解析|域名)").containsMatchIn(error) -> "dns"
        Regex("(?i)(timeout|timed out|超时)").containsMatchIn(error) -> "timeout"
        Regex("(?i)(network|socket|disconnect|reset|断开|网络|连接)").containsMatchIn(error) -> "network"
        else -> "other"
    }
}
