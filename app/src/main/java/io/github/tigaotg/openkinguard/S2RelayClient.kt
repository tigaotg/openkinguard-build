package io.github.tigaotg.openkinguard

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/** Distinguishes server-side routing errors from signed application denials. */
internal class RelayRouteException(val relayCode: String, message: String) : IOException(message)

/** The relay only accepted an ephemeral queue item; no recipient ACK was received. */
internal class RelayQueuedException : IOException(
    "对方暂未上线：通知仅在该中继内存中暂存（最多5分钟），尚未收到设备签名回执；请勿视为已送达")

/** Optional, authenticated, reconnecting WSS transport. This is not a wake-up guarantee. */
internal class S2RelayClient(
    private val keys: AndroidDeviceKeyStore,
    private val onChanged: () -> Unit,
    private val acceptInbound: (ByteArray) -> ByteArray,
    private val acceptPairing: (String, String) -> Boolean,
    private val acceptPairingCancel: (String, String) -> Unit,
) {
    private val defaultClient = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "okg-relay-reconnect").apply { isDaemon = true }
    }
    private val inbound = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(12), { task ->
            Thread(task, "okg-relay-inbound").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy())
    private val pending = ConcurrentHashMap<String, CompletableFuture<ByteArray>>()
    private val pendingPeers = ConcurrentHashMap<String, CompletableFuture<List<String>>>()
    private val pendingPairing = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var enabled = false
    @Volatile private var endpoint = ""
    @Volatile var status = "未启用公网中继"
        private set
    @Volatile var connected = false
        private set
    @Volatile var socketConnected = false
        private set
    @Volatile var lastCommunicationAtMillis = 0L
        private set
    @Volatile var lastFailureReason: String? = null
        private set
    @Volatile private var networkAvailable = true
    @Volatile private var familyCapabilities: Set<String> = emptySet()
    val supportsFamilyPairing: Boolean
        get() = connected && "family-peers-v1" in familyCapabilities &&
            "family-pair-v1" in familyCapabilities
    private var reconnectAttempt = 0
    private var epoch = 0L

    companion object {
        fun normalize(url: String): String {
            val trimmed = url.trim().removeSuffix("/")
            val parsed = URI(trimmed)
            val fragment = parsed.rawFragment
            require(fragment == null || fragment.matches(Regex("spki=[0-9a-f]{64}"))) {
                "免域名节点必须填写 #spki= 加64位公钥指纹；普通WSS地址不要填写片段"
            }
            require(parsed.scheme == "wss" && !parsed.host.isNullOrBlank() &&
                parsed.userInfo == null && parsed.query == null &&
                (parsed.path.isNullOrBlank() || parsed.path == "/relay")) {
                "公网中继需要 wss:// 域名或地址（HTTPS/TLS），路径只能为 /relay"
            }
            val address = trimmed.substringBefore('#')
            val suffix = fragment?.let { "#$it" } ?: ""
            return (if (parsed.path.isNullOrBlank()) "$address/relay" else address) + suffix
        }

        private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder()
            .withoutPadding().encodeToString(bytes)
        private fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
    }

    @Synchronized
    fun configure(wssUrl: String, shouldConnect: Boolean, hasDefaultNetwork: Boolean) {
        val parsed = if (shouldConnect) normalize(wssUrl) else ""
        if (endpoint == parsed && enabled == shouldConnect && (socket != null || !shouldConnect)) return
        epoch++
        socket?.close(1000, "configuration changed")
        socket = null
        socketConnected = false
        connected = false
        familyCapabilities = emptySet()
        failPending("中继配置已变更")
        enabled = shouldConnect
        networkAvailable = shouldConnect && hasDefaultNetwork
        endpoint = parsed
        reconnectAttempt = 0
        lastFailureReason = null
        status = when {
            !shouldConnect -> "未启用公网中继"
            !networkAvailable -> "等待系统默认网络"
            else -> "正在连接公网中继…"
        }
        onChanged()
        if (shouldConnect && networkAvailable) connect(epoch)
    }

    @Synchronized
    private fun connect(currentEpoch: Long) {
        if (!enabled || !networkAvailable || currentEpoch != epoch || socket != null) return
        socketConnected = false
        status = "连接中：等待中继身份挑战"
        onChanged()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (currentEpoch != epoch || socket !== webSocket) {
                    webSocket.close(1000, "stale network generation")
                    return
                }
                socketConnected = true
                status = "WSS已连接，等待中继身份挑战"
                onChanged()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    if (text.length > 24_000) throw IOException("中继消息过长")
                    val json = JSONObject(text)
                    when (json.getString("t")) {
                        "challenge" -> {
                            val nonce = unb64(json.getString("n"))
                            require(nonce.size == 32) { "中继挑战格式错误" }
                            val signature = keys.signRelayChallenge(nonce)
                            webSocket.send(JSONObject().put("t", "hello")
                                .put("key", b64(keys.publicKeyBytes()))
                                .put("sig", b64(signature)).toString())
                        }
                        "ready" -> {
                            require(json.getString("fp") == keys.createOrLoad().fingerprint) {
                                "中继身份返回不匹配"
                            }
                            if (currentEpoch != epoch) return
                            connected = true
                            reconnectAttempt = 0
                            lastFailureReason = null
                            val capabilities = json.optJSONArray("caps")
                            familyCapabilities = (0 until (capabilities?.length() ?: 0))
                                .mapNotNull { capabilities?.optString(it) }.toSet()
                            status = "公网中继已连接（已验证本机公钥）"
                            onChanged()
                        }
                        "request" -> {
                            if (!connected) return
                            val id = json.getString("id")
                            try {
                                val sender = json.getString("from")
                                val raw = unb64(json.getString("data"))
                                require(raw.size in 100..8192 && sender.length == 64)
                                // A malicious relay cannot make a forged frame look like
                                // the authenticated device that actually sent it.
                                require(io.github.tigaotg.openkinguard.security.protocol.LanTestWire
                                    .decode(raw).message.senderDeviceId.value == "okg-$sender")
                                inbound.execute {
                                    try {
                                        val reply = acceptInbound(raw)
                                        webSocket.send(JSONObject().put("t", "reply")
                                            .put("id", id).put("data", b64(reply)).toString())
                                    } catch (_: Exception) {
                                        // Do not expose location-consent, pairings, or local
                                        // exception details to this untrusted forwarder.
                                        webSocket.send(JSONObject().put("t", "reject")
                                            .put("id", id).put("code", "denied").toString())
                                    }
                                }
                            } catch (_: java.util.concurrent.RejectedExecutionException) {
                                webSocket.send(JSONObject().put("t", "reject")
                                    .put("id", id).put("code", "busy").toString())
                            } catch (_: Exception) {
                                // Malformed packets from other signed-in peers must not
                                // force this device's entire relay session offline.
                                webSocket.send(JSONObject().put("t", "reject")
                                    .put("id", id).put("code", "denied").toString())
                            }
                        }
                        "peer_list" -> {
                            val peers = json.getJSONArray("peers")
                            require(peers.length() <= 64)
                            val list = (0 until peers.length()).map { peers.getString(it) }
                            require(list.all { it.matches(Regex("[0-9a-f]{64}")) })
                            pendingPeers.remove(json.getString("id"))?.complete(list)
                        }
                        "pair_request" -> {
                            if (!supportsFamilyPairing) return
                            val requestId = json.getString("id")
                            val sender = json.getString("from")
                            val signed = json.getString("frame")
                            require(sender.matches(Regex("[0-9a-f]{64}")) &&
                                signed.length in 100..4000)
                            try {
                                inbound.execute {
                                    val accepted = runCatching { acceptPairing(sender, signed) }
                                        .getOrDefault(false)
                                    webSocket.send(JSONObject().put("t", "pair_reply")
                                        .put("id", requestId).put("ok", accepted).toString())
                                }
                            } catch (_: java.util.concurrent.RejectedExecutionException) {
                                webSocket.send(JSONObject().put("t", "pair_reply")
                                    .put("id", requestId).put("ok", false).toString())
                            }
                        }
                        "pair_cancelled" -> {
                            if (supportsFamilyPairing) {
                                val sender = json.getString("from")
                                val session = json.getString("session")
                                require(sender.matches(Regex("[0-9a-f]{64}")) &&
                                    session.length == 36)
                                inbound.execute { acceptPairingCancel(sender, session) }
                            }
                        }
                        "pair_ack" -> pendingPairing.remove(json.getString("id"))?.complete(true)
                        "pair_error" -> pendingPairing.remove(json.getString("id"))
                            ?.completeExceptionally(IOException("家庭中继绑定请求未被对方接收，请重试或检查对方是否在线"))
                        "reply" -> {
                            val id = json.getString("id")
                            val bytes = unb64(json.getString("data"))
                            require(bytes.size in 100..8192)
                            pending.remove(id)?.complete(bytes)
                        }
                        "error" -> {
                            val code = json.optString("code")
                            val description = when (code) {
                                "offline" -> "对方设备未连接同一个公网中继"
                                "timeout" -> "对方网络不畅或没有及时回复"
                                "busy" -> "中继或对方设备暂时繁忙"
                                "queue_full" -> "中继离线暂存队列已满，未接受本次通知"
                                "duplicate" -> "请求编号重复，请重试"
                                "denied" -> "中继报告对端未处理请求：可能无有效关系、未授权或报文无效；没有可验签的结果，不能据此认定对方拒绝"
                                else -> "中继未能处理请求"
                            }
                            val error = RelayRouteException(code, description)
                            val id = json.getString("id")
                            pending.remove(id)?.completeExceptionally(error)
                            pendingPairing.remove(id)?.completeExceptionally(error)
                            pendingPeers.remove(id)?.completeExceptionally(error)
                        }
                        "queued" -> pending.remove(json.getString("id"))
                            ?.completeExceptionally(RelayQueuedException())
                        else -> throw IOException("未知中继消息")
                    }
                    // Only a recognized, structurally valid relay frame advances
                    // the last-communication diagnostic; WebSocket open alone does not.
                    lastCommunicationAtMillis = System.currentTimeMillis()
                } catch (error: Exception) {
                    status = "中继协议错误：${error.message ?: "格式无效"}"
                    onChanged()
                    webSocket.close(1008, "protocol error")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                disconnected(webSocket, currentEpoch, t.message ?: "网络连接中断")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                disconnected(webSocket, currentEpoch,
                    if (code == 4403) "路由器端未绑定您（授权已取消）；请联系路由器管理员恢复" else
                        "连接已关闭（$code）", denied = code == 4403)
            }
        }
        val pin = URI(endpoint).rawFragment?.removePrefix("spki=")
        val client = if (pin == null) defaultClient else S2RelayTls.pinnedClient(pin)
        socket = client.newWebSocket(
            Request.Builder().url(endpoint.substringBefore('#')).build(), listener)
    }

    @Synchronized
    private fun disconnected(webSocket: WebSocket, currentEpoch: Long, cause: String,
                             denied: Boolean = false) {
        if (currentEpoch != epoch || socket !== webSocket) return
        socket = null
        socketConnected = false
        connected = false
        familyCapabilities = emptySet()
        lastFailureReason = cause.take(160)
        failPending("公网通信中断：$cause")
        status = if (denied) cause else "公网中继断开：" + cause.take(80) + "；稍后重连"
        onChanged()
        if (enabled && networkAvailable) {
            // Owner revocation is deliberately probed slowly; transient network
            // failures use capped equal-jitter exponential backoff.
            val delay = if (denied) 60_000L else NetworkReconnectPolicy.delayMillis(
                reconnectAttempt++, ThreadLocalRandom.current().nextDouble())
            scheduler.schedule({
                synchronized(this) { connect(currentEpoch) }
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    /** Rebinds a new WebSocket to the current Android default network. */
    @Synchronized
    fun networkChanged(hasDefaultNetwork: Boolean) {
        if (!enabled) return
        networkAvailable = hasDefaultNetwork
        epoch++
        val currentEpoch = epoch
        val old = socket
        socket = null
        old?.cancel()
        socketConnected = false
        connected = false
        familyCapabilities = emptySet()
        failPending("Android 默认网络已切换，旧中继会话已释放")
        reconnectAttempt = 0
        status = if (hasDefaultNetwork) "网络已切换，正在重新连接并重新认证"
            else "当前没有默认网络，等待网络恢复"
        lastFailureReason = if (hasDefaultNetwork) "DEFAULT_NETWORK_CHANGED"
            else "NO_DEFAULT_NETWORK"
        onChanged()
        if (hasDefaultNetwork) connect(currentEpoch)
    }

    fun request(recipientFingerprint: String, frame: ByteArray,
                queueWhenOffline: Boolean = false): ByteArray {
        require(recipientFingerprint.matches(Regex("[0-9a-f]{64}"))) { "目标设备指纹无效" }
        require(frame.size in 100..8192) { "应用消息超出中继上限" }
        val current = socket ?: throw IOException("中继尚未连接：$status")
        if (!connected) throw IOException("中继尚未完成设备身份验证：$status")
        val id = UUID.randomUUID().toString()
        val wait = CompletableFuture<ByteArray>()
        pending[id] = wait
        try {
            if (!current.send(JSONObject().put("t", "req").put("id", id)
                    .put("to", recipientFingerprint).put("data", b64(frame))
                    .put("queue", queueWhenOffline).toString())) {
                throw IOException("中继发送队列已关闭")
            }
            return try { wait.get(21, TimeUnit.SECONDS) }
                catch (error: java.util.concurrent.ExecutionException) {
                    val cause = error.cause
                    if (cause is IOException) throw cause
                    throw IOException(cause?.message ?: "对方未返回签名回执", cause)
                }
                catch (error: java.util.concurrent.TimeoutException) {
                    throw java.net.SocketTimeoutException(
                        "中继或对方设备在21秒内未返回可核验的消息；可能通信不畅或对方已离线")
                }
        } finally {
            pending.remove(id)
        }
    }

    /** Explicit user action only; listing identities is restricted to approved family nodes. */
    fun familyPeers(): List<String> {
        require(supportsFamilyPairing) { "当前节点不支持同家庭中继发现，请升级家庭中继" }
        val current = socket ?: throw IOException("家庭中继未连接")
        val id = UUID.randomUUID().toString()
        val wait = CompletableFuture<List<String>>()
        pendingPeers[id] = wait
        try {
            if (!current.send(JSONObject().put("t", "peers").put("id", id).toString()))
                throw IOException("家庭中继连接已关闭")
            return wait.get(8, TimeUnit.SECONDS)
        } finally {
            pendingPeers.remove(id)
        }
    }

    /** A received transport ACK is not a family authorization or a cryptographic grant. */
    fun sendPairing(recipient: String, signedFrame: String) {
        require(supportsFamilyPairing) { "节点不支持同家庭绑定" }
        require(recipient.matches(Regex("[0-9a-f]{64}")) && signedFrame.length in 100..4000)
        val current = socket ?: throw IOException("家庭中继未连接")
        val id = UUID.randomUUID().toString()
        val wait = CompletableFuture<Boolean>()
        pendingPairing[id] = wait
        try {
            if (!current.send(JSONObject().put("t", "pair").put("id", id)
                    .put("to", recipient).put("frame", signedFrame).toString()))
                throw IOException("家庭中继连接已关闭")
            wait.get(21, TimeUnit.SECONDS)
        } finally {
            pendingPairing.remove(id)
        }
    }

    fun cancelPairing(recipient: String, session: String) {
        if (!supportsFamilyPairing) return
        require(recipient.matches(Regex("[0-9a-f]{64}")) && session.length == 36)
        socket?.send(JSONObject().put("t", "pair_cancel")
            .put("id", UUID.randomUUID().toString()).put("to", recipient)
            .put("session", session).toString())
    }

    private fun failPending(why: String) {
        val active = pending.values.toList()
        pending.clear()
        active.forEach { it.completeExceptionally(IOException(why)) }
        pendingPeers.values.toList().forEach { it.completeExceptionally(IOException(why)) }
        pendingPeers.clear()
        pendingPairing.values.toList().forEach { it.completeExceptionally(IOException(why)) }
        pendingPairing.clear()
    }
}
