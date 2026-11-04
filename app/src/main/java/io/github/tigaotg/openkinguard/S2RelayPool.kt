package io.github.tigaotg.openkinguard

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * At most three opt-in WSS sessions. Each trusted node independently authenticates
 * this Keystore identity, so sender and recipient may reach each other through
 * any common live node; there is no central account or node-to-node federation.
 */
internal class S2RelayPool(
    keys: AndroidDeviceKeyStore,
    onChanged: () -> Unit,
    acceptInbound: (ByteArray) -> ByteArray,
) {
    private val slots = List(3) { S2RelayClient(keys, onChanged, acceptInbound) }
    @Volatile private var endpoints: List<String> = emptyList()

    companion object {
        fun normalizeList(text: String): List<String> {
            val entries = text.split(Regex("[\\r\\n;,]+"))
                .map { it.trim() }.filter { it.isNotBlank() }
            require(entries.isNotEmpty()) { "请至少填写一个可信 WSS 中继地址" }
            require(entries.size <= 3) { "当前最多可以同时配置3个中继节点" }
            val normalized = entries.map(S2RelayClient::normalize)
            require(normalized.size == normalized.distinct().size) {
                "同一个中继地址不能重复填写"
            }
            return normalized
        }
    }

    val connected: Boolean
        get() = slots.take(endpoints.size).any { it.connected }

    val status: String
        get() {
            val active = endpoints
            if (active.isEmpty()) return "未启用公网中继"
            val count = slots.take(active.size).count { it.connected }
            return "$count/${active.size} 个中继已连接；" +
                slots.take(active.size).mapIndexed { index, client ->
                    "节点${index + 1}：${client.status.take(86)}"
                }.joinToString("；")
        }

    @Synchronized
    fun configure(rawEndpoints: String, shouldConnect: Boolean) {
        val parsed = if (shouldConnect) normalizeList(rawEndpoints) else emptyList()
        endpoints = parsed
        slots.forEachIndexed { index, client ->
            if (index < parsed.size) client.configure(parsed[index], true)
            else client.configure("", false)
        }
    }

    /**
     * Search all connected relays for a signed reply first. Only SOS/check-in
     * class callers may opt into one node's short-lived offline RAM queue.
     * Queue acceptance is intentionally an exception, NEVER a delivered ACK.
     */
    fun request(recipientFingerprint: String, frame: ByteArray,
                allowTemporaryQueue: Boolean = false): ByteArray {
        val active = slots.take(endpoints.size).filter { it.connected }
        if (active.isEmpty()) throw IOException("所有自建公网中继均未连接；请检查公网可达性、WSS证书或节点公钥指纹")
        val failures = mutableListOf<String>()
        for (client in active) {
            try {
                return client.request(recipientFingerprint, frame, queueWhenOffline = false)
            } catch (error: RelayRouteException) {
                // An authenticated remote rejection must not be described as a
                // network failure or circumvented by retrying other nodes.
                if (error.relayCode in setOf("denied", "duplicate")) throw error
                failures += error.message.orEmpty()
            } catch (error: SocketTimeoutException) {
                failures += error.message.orEmpty()
            } catch (error: IOException) {
                failures += error.message.orEmpty()
            }
        }
        if (allowTemporaryQueue) {
            for (client in active) {
                if (!client.connected) continue
                try {
                    return client.request(recipientFingerprint, frame, queueWhenOffline = true)
                } catch (queued: RelayQueuedException) {
                    throw queued
                } catch (denied: RelayRouteException) {
                    if (denied.relayCode == "denied") throw denied
                    failures += denied.message.orEmpty()
                } catch (error: IOException) {
                    failures += error.message.orEmpty()
                }
            }
        }
        throw IOException("已尝试${active.size}个公网中继，未收到设备签名回执：" +
            failures.filter { it.isNotBlank() }.distinct().take(2).joinToString("；").take(170))
    }
}
