package io.github.tigaotg.openkinguard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class FamilyRelayPeer(val fingerprint: String, val slot: Int)

/** A failed family relay must not hide devices discovered on another healthy node. */
internal object FamilyRelayDiscovery {
    fun collect(nodes: List<Pair<Int, () -> List<String>>>): List<FamilyRelayPeer> {
        val found = mutableListOf<FamilyRelayPeer>()
        var succeeded = 0
        var failed = 0
        nodes.forEach { (slot, query) ->
            try {
                found += query().map { FamilyRelayPeer(it, slot) }
                succeeded++
            } catch (_: Exception) {
                failed++
            }
        }
        // If one node failed and the others found nobody, an empty list would
        // misleadingly say that the family has no online devices.
        if (failed > 0 && (succeeded == 0 || found.isEmpty())) {
            throw IOException("部分家庭中继查询失败，在线设备列表可能不完整，请检查节点连接后重试")
        }
        return found.distinctBy { it.fingerprint }
    }
}

/**
 * At most three opt-in WSS sessions. Each trusted node independently authenticates
 * this Keystore identity, so sender and recipient may reach each other through
 * any common live node; there is no central account or node-to-node federation.
 */
internal class S2RelayPool(
    context: Context,
    keys: AndroidDeviceKeyStore,
    private val onChanged: () -> Unit,
    acceptInbound: (ByteArray) -> ByteArray,
    acceptPairing: (Int, String, String) -> Boolean,
    acceptPairingCancel: (Int, String, String) -> Unit,
) {
    private val connectivity = context.applicationContext.getSystemService(
        Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "okg-private-relay-network").apply { isDaemon = true }
    }
    private var networkCallbackRegistered = false
    private var lastDefaultNetwork: Network? = null
    private var pendingNetworkCheck: ScheduledFuture<*>? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkRecheck()
        override fun onLost(network: Network) = scheduleNetworkRecheck()
        override fun onCapabilitiesChanged(
            network: Network, capabilities: android.net.NetworkCapabilities
        ) {
            if (connectivity.activeNetwork == network) scheduleNetworkRecheck()
        }
    }

    private val slots = List(3) { index ->
        S2RelayClient(keys, onChanged, acceptInbound,
            { sender, signed -> acceptPairing(index, sender, signed) },
            { sender, session -> acceptPairingCancel(index, sender, session) })
    }
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

    val familyReady: Boolean
        get() = slots.take(endpoints.size).any { it.supportsFamilyPairing }

    fun familyPeers(): List<FamilyRelayPeer> = FamilyRelayDiscovery.collect(slots.take(endpoints.size)
        .mapIndexedNotNull { index, client ->
            if (client.supportsFamilyPairing) index to { client.familyPeers() } else null
        })

    fun resolveFamilyPeer(fingerprint: String): FamilyRelayPeer {
        require(Regex("[0-9a-f]{64}").matches(fingerprint)) { "家庭设备指纹格式无效" }
        return familyPeers().firstOrNull { it.fingerprint == fingerprint }
            ?: throw IOException("家庭中继当前未发现目标设备；请让对方保持联网后重新查找")
    }

    fun sendPairing(peer: FamilyRelayPeer, signedFrame: String) {
        require(peer.slot in endpoints.indices && slots[peer.slot].supportsFamilyPairing) {
            "之前发现的家庭中继已断开，请重新查找设备"
        }
        slots[peer.slot].sendPairing(peer.fingerprint, signedFrame)
    }

    fun cancelPairing(peer: FamilyRelayPeer, session: String) {
        if (peer.slot in endpoints.indices) slots[peer.slot]
            .cancelPairing(peer.fingerprint, session)
    }

    val status: String
        get() {
            val active = endpoints
            if (active.isEmpty()) return "未启用公网中继"
            val selected = slots.take(active.size)
            val sockets = selected.count { it.socketConnected }
            val authenticated = selected.count { it.connected }
            val network = if (lastDefaultNetwork == null) "无默认网络" else "默认网络可用"
            val now = System.currentTimeMillis()
            return sockets.toString() + "/" + active.size +
                " 个WSS连接，" + authenticated + "/" + active.size +
                " 个设备身份已认证（" + network + "）；" +
                selected.mapIndexed { index, client ->
                    val age = if (client.lastCommunicationAtMillis > 0L)
                        ((now - client.lastCommunicationAtMillis).coerceAtLeast(0L) / 1000L)
                            .toString() + "秒前收到消息"
                    else "尚无有效消息"
                    val failure = client.lastFailureReason?.let { "；最近失败：" + it.take(60) }.orEmpty()
                    "节点" + (index + 1) + "：" + client.status.take(80) +
                        "（" + age + failure + "）"
                }.joinToString("；")
        }

    @Synchronized
    fun configure(rawEndpoints: String, shouldConnect: Boolean) {
        val parsed = if (shouldConnect) normalizeList(rawEndpoints) else emptyList()
        endpoints = parsed
        if (shouldConnect) {
            registerNetworkCallback()
            lastDefaultNetwork = connectivity.activeNetwork
        } else {
            pendingNetworkCheck?.cancel(false)
            pendingNetworkCheck = null
            if (networkCallbackRegistered) {
                runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
                networkCallbackRegistered = false
            }
            lastDefaultNetwork = null
        }
        val hasNetwork = shouldConnect && lastDefaultNetwork != null
        slots.forEachIndexed { index, client ->
            if (index < parsed.size) client.configure(parsed[index], true, hasNetwork)
            else client.configure("", false, false)
        }
    }

    @Synchronized
    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        runCatching {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun scheduleNetworkRecheck() {
        synchronized(this) {
            pendingNetworkCheck?.cancel(false)
            pendingNetworkCheck = networkScheduler.schedule({
                val changed = synchronized(this) {
                    if (endpoints.isEmpty()) return@schedule
                    val actual = connectivity.activeNetwork
                    if (actual == lastDefaultNetwork) return@schedule
                    lastDefaultNetwork = actual
                    slots.take(endpoints.size).forEach { client ->
                        client.networkChanged(actual != null)
                    }
                    true
                }
                if (changed) onChanged()
            }, 900, TimeUnit.MILLISECONDS)
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
