package io.github.tigaotg.openkinguard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Optional public NIP-01 signaling transport. Public nodes carry authenticated
 * contact cards, encrypted UDP candidates and short-lived encrypted pairing
 * handshakes only. No SOS, GPS or application business frame may pass here.
 *
 * This is foreground/explicit background listening only; it cannot wake a killed app.
 */
internal class S2NostrTransport(
    context: Context,
    private val deviceKeys: AndroidDeviceKeyStore,
    private val pairings: LocalPairingManager,
    private val onChanged: () -> Unit,
) {
    /** Callbacks receive only validated, paired identities and decrypted candidates. */
    @Volatile var directCandidates: ((String, List<S5DirectCandidatePolicy.Candidate>) -> Unit)? = null
    @Volatile var directContact: ((String) -> Unit)? = null
    @Volatile var directReady: (() -> Unit)? = null
    @Volatile var directNetworkChanged: (() -> Unit)? = null
    /** Unpaired invitations are accepted only for a locally opened random room. */
    @Volatile var remotePairingInbound: ((JSONObject) -> Unit)? = null
    @Volatile private var remoteRoom: String? = null
    private val crypto = S2PublicNostrCrypto(context, deviceKeys)
    private val identity by lazy { crypto.load() }
    private val ownFingerprint by lazy { deviceKeys.createOrLoad().fingerprint }
    private val http = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
        as ConnectivityManager
    private val reconnect = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "okg-nostr-reconnect").apply { isDaemon = true }
    }
    private val inbound = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(16), { task ->
            Thread(task, "okg-nostr-packet").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy())
    private val contacts = ConcurrentHashMap<String, Contact>()
    private val sockets = mutableListOf<Slot>()
    @Volatile private var enabled = false
    @Volatile private var epoch = 0L
    private var networkCallbackRegistered = false
    private var lastDefaultNetwork: Network? = null
    private var pendingDirectAddressRefresh: ScheduledFuture<*>? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkRecheck()
        override fun onLost(network: Network) = scheduleNetworkRecheck()
        override fun onCapabilitiesChanged(
            network: Network, capabilities: android.net.NetworkCapabilities
        ) {
            if (connectivity.activeNetwork == network) scheduleNetworkRecheck()
        }
        override fun onLinkPropertiesChanged(
            network: Network, properties: android.net.LinkProperties
        ) {
            if (connectivity.activeNetwork == network) scheduleDirectAddressRefresh(network)
        }
    }

    data class Contact(val fingerprint: String, val encryptionPublic: String,
                       val issued: Long, val expires: Long, val nostrPublic: String)

    private inner class Slot(val url: String, val generation: Long) {
        var socket: WebSocket? = null
        @Volatile var live = false
        // A WebSocket connection is NOT proof that a Nostr relay accepts our
        // subscriptions or application events. NIP-01 requires EOSE and OK.
        @Volatile var inboxSubscribed = false
        @Volatile var cardAccepted = false
        @Volatile var blocked = false
        @Volatile var cardEventId: String? = null
        @Volatile var message = "正在连接"
        @Volatile var lastCommunicationAtMillis = 0L
        @Volatile var lastFailureReason: String? = null
        var reconnectAttempt = 0

        val ready: Boolean
            get() = live && inboxSubscribed && cardAccepted && !blocked
    }

    companion object {
        // Checked from three mainland-China datacenter probes on 2026-10-31.
        // HTTPS/TLS reachability does NOT establish WSS publish/subscribe reliability.
        const val DEFAULT_RELAYS = "wss://relay.nostr.net\nwss://relay.nostr.info\nwss://relay.kizuna-miki.com"
        const val MAINLAND_ALTERNATIVE_RELAYS =
            "wss://relay.nostr.net\nwss://relay.nostr.info\nwss://relay.nostr.wirednet.jp"
        private const val PREVIOUS_BUNDLED_RELAYS =
            "wss://relay.nostr.net\nwss://relay.nostr.info\nwss://relay.nostr.wirednet.jp"
        private const val LEGACY_DEFAULT_RELAYS = "wss://relay.primal.net\nwss://nos.lol"

        /** Upgrade exact historical bundles; preserve every custom relay list. */
        fun migrateBundledDefaults(saved: String): String =
            if (saved.trim() == LEGACY_DEFAULT_RELAYS ||
                saved.trim() == PREVIOUS_BUNDLED_RELAYS) DEFAULT_RELAYS else saved

        fun normalizeList(text: String): List<String> {
            val result = text.split(Regex("[\r\n,;]+"))
                .map { it.trim().removeSuffix("/") }.filter { it.isNotBlank() }
            require(result.isNotEmpty() && result.size <= 3) { "公共节点需填写1至3个地址" }
            result.forEach { value ->
                val uri = URI(value)
                require(uri.scheme == "wss" && !uri.host.isNullOrBlank() &&
                    uri.userInfo == null && uri.fragment == null && uri.query == null &&
                    uri.path.isNullOrBlank()) {
                    "Nostr 节点需要 wss:// 域名；这里不能填写专用中继 /relay 地址"
                }
            }
            require(result.distinct().size == result.size) { "公共节点地址不可重复" }
            return result
        }
    }

    val connected: Boolean
        get() = synchronized(this) { sockets.any { it.ready } }

    fun ownRemoteContactCard(): JSONObject = crypto.card(identity)

    fun sealRemotePairing(bytes: ByteArray, recipientPublic: String,
                          aad: ByteArray): JSONObject =
        crypto.seal(bytes, recipientPublic, aad).json()

    fun openRemotePairing(box: JSONObject, aad: ByteArray): ByteArray =
        crypto.open(S2PublicNostrCrypto.Sealed.parse(box), identity.agreementPrivate, aad)

    fun verifyRemoteContact(card: JSONObject, fingerprint: String,
                            signingPublicKey: String, eventNostrPubkey: String): Boolean =
        crypto.verifyCard(card, SavedPeer("", fingerprint, signingPublicKey,
            PairingRole.HELPER, 3), eventNostrPubkey)

    /** The random room name is NOT the identity of a trusted user. */
    @Synchronized
    fun setRemoteRoom(room: String?) {
        require(room == null || room.matches(Regex("[0-9a-f]{64}")))
        if (room == remoteRoom) return
        remoteRoom = room
        sockets.filter { it.live }.forEach { slot ->
            slot.socket?.let { ws ->
                ws.send(JSONArray().put("CLOSE").put("okg-remote").toString())
                if (room != null) subscribeRemote(ws, room)
            }
        }
        onChanged()
    }

    fun publishRemotePairing(room: String, payload: JSONObject,
                             expires: Long): Boolean {
        require(room.matches(Regex("[0-9a-f]{64}")) && room == remoteRoom)
        require(payload.optString("type") == "remote-pairing")
        require(expires in (System.currentTimeMillis() + 1_000L)..
            (System.currentTimeMillis() + 11 * 60_000L))
        val event = S2NostrWire.sign(identity.nostrSecret, room, payload.toString(),
            "OKG-remote-${java.util.UUID.randomUUID()}", expires)
        val wire = JSONArray().put("EVENT").put(event).toString()
        return synchronized(this) {
            enabled && sockets.filter { it.ready }
                .map { it.socket?.send(wire) == true }.any { it }
        }
    }

    val status: String
        get() = synchronized(this) {
            if (!enabled) "公共 Nostr 节点未启用"
            else {
                val webSockets = sockets.count { it.live }
                val subscriptions = sockets.count { it.inboxSubscribed && !it.blocked }
                val announcements = sockets.count { it.cardAccepted && !it.blocked }
                val ready = sockets.count { it.ready }
                val network = if (connectivity.activeNetwork == null) "无默认网络" else "默认网络可用"
                "WSS " + webSockets + "/" + sockets.size +
                    "；收件订阅 " + subscriptions + "/" + sockets.size +
                    "；身份公告接受 " + announcements + "/" + sockets.size +
                    "；完整信令就绪 " + ready + "/" + sockets.size +
                    "（" + network + "）；" +
                    sockets.mapIndexed { i, item ->
                        val age = if (item.lastCommunicationAtMillis > 0L)
                            ((System.currentTimeMillis() - item.lastCommunicationAtMillis)
                                .coerceAtLeast(0L) / 1000L).toString() + "秒前收到消息"
                        else "尚无有效消息"
                        val failure = item.lastFailureReason?.let { "；最近失败：" + it.take(50) }.orEmpty()
                        "节点" + (i + 1) + "：" + item.message.take(80) +
                            "（" + age + failure + "）"
                    }.joinToString("；")
            }
        }

    /** Contact is trusted only after the original QR-pinned key signs its binding. */
    fun verifiedContact(fingerprint: String): Contact? {
        val item = contacts[fingerprint] ?: return null
        val now = System.currentTimeMillis()
        return item.takeIf {
            it.expires > now && pairings.snapshot().peers.any { p ->
                p.active && p.peerFingerprint == fingerprint
            }
        }
    }

    /** Public relay only carries ECDH-encrypted candidate endpoints, never business data. */
    fun publishCandidates(target: String,
                          items: List<S5DirectCandidatePolicy.Candidate>): Boolean {
        val card = verifiedContact(target) ?: return false
        val now = System.currentTimeMillis()
        val candidates = items.filter { S5DirectCandidatePolicy.fresh(it, now) }.take(8)
        if (candidates.isEmpty()) return false
        val expires = candidates.minOf { it.expires }.coerceAtMost(now + 90_000)
        val data = JSONObject().put("v", 1).put("from", ownFingerprint)
            .put("to", target).put("expires", expires)
            .put("candidates", JSONArray(candidates.map {
                JSONObject().put("ip", it.ip).put("port", it.port)
            })).toString().toByteArray(Charsets.UTF_8)
        val aad = crypto.associated(target, ownFingerprint, "s5-candidates-v1", expires)
        val sealed = crypto.seal(data, card.encryptionPublic, aad)
        val payload = JSONObject().put("type", "candidates").put("sender", ownFingerprint)
            .put("recipient", target).put("expires", expires).put("box", sealed.json())
        val event = S2NostrWire.sign(identity.nostrSecret, target, payload.toString(),
            "OpenKinGuard-s5-direct-${target.take(12)}", expires)
        val wire = JSONArray().put("EVENT").put(event).toString()
        return synchronized(this) {
            if (!enabled) false
            else sockets.filter { it.ready }.map { it.socket?.send(wire) == true }.any { it }
        }
    }

    @Synchronized
    fun configure(addresses: String, turnOn: Boolean) {
        val urls = if (turnOn) normalizeList(addresses) else emptyList()
        if (turnOn && enabled && sockets.map { it.url } == urls) return
        epoch++
        sockets.forEach { it.socket?.close(1000, "settings changed") }
        sockets.clear()
        enabled = turnOn
        contacts.clear()
        if (turnOn) {
            // A failure to unwrap a saved secret stops activation, not silent identity replacement.
            identity
            lastDefaultNetwork = connectivity.activeNetwork
            if (!networkCallbackRegistered) {
                runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
                    .onSuccess { networkCallbackRegistered = true }
            }
            urls.forEach { sockets += Slot(it, epoch) }
            if (lastDefaultNetwork != null) sockets.forEach(::connect)
            else sockets.forEach { it.message = "没有默认网络，等待网络恢复" }
            scheduleCardRefresh(epoch)
        } else {
            pendingDirectAddressRefresh?.cancel(false)
            pendingDirectAddressRefresh = null
            if (networkCallbackRegistered) {
                runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
                networkCallbackRegistered = false
            }
            lastDefaultNetwork = null
        }
        onChanged()
    }

    private fun scheduleNetworkRecheck() {
        // Debounce the default-network switch before replacing every WSS session.
        reconnect.schedule({
            synchronized(this) {
                if (!enabled) return@synchronized
                val actual = connectivity.activeNetwork
                if (actual == lastDefaultNetwork) return@synchronized
                lastDefaultNetwork = actual
                directNetworkChanged?.invoke()
                sockets.forEach { slot ->
                    val oldSocket = slot.socket
                    slot.socket = null
                    slot.live = false
                    slot.inboxSubscribed = false
                    slot.cardAccepted = false
                    slot.cardEventId = null
                    slot.blocked = false
                    slot.reconnectAttempt = 0
                    slot.lastFailureReason = "DEFAULT_NETWORK_CHANGED"
                    slot.message = if (actual == null) "没有默认网络，等待网络恢复"
                        else "网络已切换，正在重新接入公共节点并恢复订阅"
                    oldSocket?.cancel()
                    if (actual != null) connect(slot)
                }
                onChanged()
            }
        }, 1200, TimeUnit.MILLISECONDS)
    }

    /** IPv6 privacy-address or route changes can occur without a new Network object. */
    private fun scheduleDirectAddressRefresh(network: Network) {
        synchronized(this) {
            pendingDirectAddressRefresh?.cancel(false)
            pendingDirectAddressRefresh = reconnect.schedule({
                if (enabled && connectivity.activeNetwork == network) {
                    directNetworkChanged?.invoke()
                }
            }, 1200, TimeUnit.MILLISECONDS)
        }
    }

    private fun scheduleCardRefresh(generation: Long) {
        reconnect.schedule({
            if (generation == epoch && enabled) {
                synchronized(this) {
                    sockets.filter { it.live }.forEach { slot ->
                        slot.socket?.let { publishCard(slot, it) }
                    }
                }
                scheduleCardRefresh(generation)
            }
        }, 6, TimeUnit.HOURS)
    }

    @Synchronized
    private fun connect(slot: Slot) {
        if (!enabled || connectivity.activeNetwork == null ||
            slot.generation != epoch || slot.socket != null) return
        slot.message = "连接中"
        onChanged()
        slot.socket = http.newWebSocket(Request.Builder().url(slot.url).build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (slot.generation != epoch || slot.socket !== ws) {
                        ws.close(1000, "stale"); return
                    }
                    slot.live = true
                    slot.lastFailureReason = null
                    slot.inboxSubscribed = false
                    slot.cardAccepted = false
                    slot.blocked = false
                    slot.cardEventId = null
                    slot.reconnectAttempt = 0
                    slot.message = "WebSocket已连接，等待节点确认订阅与身份公告"
                    onChanged()
                    runCatching {
                        subscribe(ws)
                        publishCard(slot, ws)
                    }.onFailure { error ->
                        slot.blocked = true
                        slot.message = "订阅或公告发送失败：${error.message.orEmpty().take(60)}"
                        onChanged()
                    }
                    // Never leave a silent WSS-only connection labelled as usable.
                    reconnect.schedule({
                        if (slot.generation == epoch && slot.live && !slot.ready &&
                            !slot.blocked && slot.socket === ws) {
                            slot.message = "节点12秒内未确认订阅或身份公告；可能不支持应用事件"
                            onChanged()
                        }
                    }, 12, TimeUnit.SECONDS)
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    if (text.length > 26_000 || slot.generation != epoch ||
                        slot.socket !== ws) return
                    try {
                        val message = JSONArray(text)
                        slot.lastCommunicationAtMillis = System.currentTimeMillis()
                        when (message.getString(0)) {
                            "EVENT" -> {
                                val event = message.getJSONObject(2)
                                inbound.execute { process(event) }
                            }
                            "NOTICE" -> {
                                slot.message = "节点提示：${message.optString(1).take(70)}"
                                onChanged()
                            }
                            "EOSE" -> if (message.optString(1) == "okg-inbox") {
                                slot.inboxSubscribed = true
                                refreshSlotStatus(slot)
                            }
                            "CLOSED" -> if (message.optString(1) == "okg-inbox") {
                                slot.inboxSubscribed = false
                                slot.blocked = true
                                slot.message = "节点拒绝收件订阅：${message.optString(2).take(85)}"
                                onChanged()
                            }
                            "AUTH" -> {
                                slot.blocked = true
                                slot.message = "节点要求Nostr登录授权，当前匿名公共模式不可使用该节点"
                                onChanged()
                            }
                            "OK" -> {
                                val eventId = message.optString(1)
                                val accepted = message.optBoolean(2)
                                if (eventId == slot.cardEventId) {
                                    slot.cardAccepted = accepted
                                    if (accepted) refreshSlotStatus(slot)
                                    else {
                                        slot.blocked = true
                                        slot.message = "节点拒收本机身份公告：${message.optString(3).take(85)}"
                                        onChanged()
                                    }
                                } else if (!accepted) {
                                    slot.blocked = true
                                    slot.message = "节点拒收信令：${message.optString(3).take(85)}"
                                    onChanged()
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // External relay content is untrusted; one bad frame is not a disconnect.
                    }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    disconnected(slot, ws, t.message ?: "连接中断")
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    disconnected(slot, ws, "断开（$code）")
                }
            })
    }

    @Synchronized
    private fun disconnected(slot: Slot, ws: WebSocket, reason: String) {
        if (slot.generation != epoch || slot.socket !== ws) return
        slot.socket = null
        slot.live = false
        slot.inboxSubscribed = false
        slot.cardAccepted = false
        slot.blocked = false
        slot.cardEventId = null
        slot.lastFailureReason = reason.take(160)
        slot.message = "断线重连：" + reason.take(45)
        if (enabled && connectivity.activeNetwork != null) {
            val delay = NetworkReconnectPolicy.delayMillis(
                slot.reconnectAttempt++, ThreadLocalRandom.current().nextDouble())
            reconnect.schedule({ synchronized(this) { connect(slot) } },
                delay, TimeUnit.MILLISECONDS)
        } else if (enabled) {
            slot.message = "网络不可用，等待系统恢复网络"
        }
        onChanged()
    }


    private fun refreshSlotStatus(slot: Slot) {
        if (slot.blocked || !slot.live) return
        slot.message = when {
            slot.ready -> "信令订阅和身份公告均获节点确认；还未建立直连"
            slot.inboxSubscribed -> "收件订阅已确认，等待本机身份公告被接受"
            slot.cardAccepted -> "身份公告已接受，等待收件订阅确认"
            else -> "WebSocket已连接，等待节点确认订阅与身份公告"
        }
        onChanged()
        if (slot.ready) directReady?.invoke()
    }

    private fun subscribe(ws: WebSocket) {
        val inbox = JSONObject().put("kinds", JSONArray().put(S2NostrWire.KIND))
            .put("#k", JSONArray().put(ownFingerprint))
            .put("since", System.currentTimeMillis() / 1000L - 90L)
            .put("limit", 80)
        check(ws.send(JSONArray().put("REQ").put("okg-inbox").put(inbox).toString())) {
            "WebSocket未能发送订阅请求"
        }
        remoteRoom?.let { subscribeRemote(ws, it) }
        queryCards(ws)
    }

    private fun subscribeRemote(ws: WebSocket, room: String) {
        val filter = JSONObject().put("kinds", JSONArray().put(S2NostrWire.KIND))
            .put("#k", JSONArray().put(room))
            .put("since", System.currentTimeMillis() / 1000L - 11 * 60L)
            .put("limit", 100)
        ws.send(JSONArray().put("REQ").put("okg-remote").put(filter).toString())
    }

    private fun queryCards(ws: WebSocket) {
        val peers = pairings.snapshot().peers.filter { it.active }
            .map { it.peerFingerprint }.distinct().take(64)
        if (peers.isEmpty()) return
        val filter = JSONObject().put("kinds", JSONArray().put(S2NostrWire.KIND))
            .put("#k", JSONArray(peers)).put("limit", 80)
        ws.send(JSONArray().put("REQ").put("okg-contacts").put(filter).toString())
    }

    private fun publishCard(slot: Slot, ws: WebSocket) {
        val card = crypto.card(identity)
        val content = JSONObject().put("type", "contact").put("data", card).toString()
        val event = S2NostrWire.sign(identity.nostrSecret, ownFingerprint, content,
            "OpenKinGuard-contact-v1", card.getLong("exp"))
        // Set before send: the relay can reply immediately on a fast connection.
        slot.cardEventId = event.getString("id")
        check(ws.send(JSONArray().put("EVENT").put(event).toString())) {
            "WebSocket未能发送身份公告"
        }
    }

    private fun process(event: JSONObject) {
        try {
            if (!S2NostrWire.verify(event)) return
            val route = S2NostrWire.routeTag(event) ?: return
            val content = JSONObject(event.getString("content"))
            when (content.optString("type")) {
                "remote-pairing" -> {
                    if (route != remoteRoom || !connected) return
                    remotePairingInbound?.invoke(event)
                }
                "contact" -> {
                    if (route == ownFingerprint) return
                    val peer = pairings.snapshot().peers.firstOrNull {
                        it.active && it.peerFingerprint == route
                    } ?: return
                    val card = content.getJSONObject("data")
                    if (!crypto.verifyCard(card, peer, event.getString("pubkey"))) return
                    val candidate = Contact(route, card.getString("epub"),
                        card.getLong("iat"), card.getLong("exp"), card.getString("npub"))
                    contacts.compute(route) { _, current ->
                        if (current == null || candidate.issued >= current.issued) candidate
                        else current
                    }
                    onChanged()
                    directContact?.invoke(route)
                }
                "candidates" -> {
                    val sender = content.optString("sender")
                    if (sender == ownFingerprint ||
                        content.optString("recipient") != ownFingerprint ||
                        route != ownFingerprint) return
                    val card = verifiedContact(sender) ?: return
                    if (card.nostrPublic != event.getString("pubkey")) return
                    val expires = content.getLong("expires")
                    val now = System.currentTimeMillis()
                    if (expires !in (now + 1000)..(now + 120_000)) return
                    val aad = crypto.associated(ownFingerprint, sender,
                        "s5-candidates-v1", expires)
                    val plaintext = crypto.open(S2PublicNostrCrypto.Sealed.parse(
                        content.getJSONObject("box")), identity.agreementPrivate, aad)
                    val body = JSONObject(String(plaintext, Charsets.UTF_8))
                    if (body.getInt("v") != 1 || body.getString("from") != sender ||
                        body.getString("to") != ownFingerprint ||
                        body.getLong("expires") != expires) return
                    val raw = body.getJSONArray("candidates")
                    if (raw.length() > 8) return
                    val accepted = (0 until raw.length()).mapNotNull { i ->
                        val element = raw.optJSONObject(i) ?: return@mapNotNull null
                        S5DirectCandidatePolicy.accept(element.optString("ip"),
                            element.optInt("port"), expires, now)
                    }
                    if (accepted.isNotEmpty()) directCandidates?.invoke(sender, accepted)
                }
                "packet" -> {
                    // Explicit S5 migration boundary. Even an encrypted packet
                    // carries BUSINESS data and is forbidden on public relays.
                    return
                }
            }
        } catch (_: Exception) {
            // Malicious public messages must never interrupt app listening.
        }
    }

    /** S5 boundary: public relay must not expose an application-data API. */
}
