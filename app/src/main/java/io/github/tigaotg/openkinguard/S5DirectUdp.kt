package io.github.tigaotg.openkinguard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import io.github.tigaotg.openkinguard.core.engine.S5StunCodec
import io.github.tigaotg.openkinguard.security.protocol.LanTestWire
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Experimental direct transport: one persistent UDP socket for STUN, bilateral
 * NAT punching, and encrypted signed request/reply. The manager MUST verify a
 * signed application receipt before declaring any request delivered.
 *
 * This is an ICE-inspired pilot, not a complete RFC 8445 ICE stack: no TURN,
 * priority nomination, fragmentation protocol, or background wake guarantee.
 */
internal class S5DirectUdp(
    context: Context,
    private val keys: AndroidDeviceKeyStore,
    private val pairings: LocalPairingManager,
    private val signal: S2NostrTransport,
    private val receiveSigned: (ByteArray) -> ByteArray,
    private val onChanged: () -> Unit,
    private val requestProbe: () -> Unit,
) {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE)
        as ConnectivityManager
    private val crypto = S2PublicNostrCrypto(app, keys)
    private val localIdentity by lazy { crypto.load() }
    private val ownFingerprint by lazy { keys.createOrLoad().fingerprint }
    private val random = SecureRandom()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "okg-s5-udp-schedule").apply { isDaemon = true }
    }
    private val work = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(24), { task ->
            Thread(task, "okg-s5-udp-inbound").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy())
    private val pending = ConcurrentHashMap<String, Pending>()
    private val stun = ConcurrentHashMap<String, StunPending>()
    private val peerCandidates = ConcurrentHashMap<String, List<S5DirectCandidatePolicy.Candidate>>()
    private val verifiedRoutes = ConcurrentHashMap<String, Route>()
    private val publishAt = ConcurrentHashMap<String, Long>()
    private val seenRequests = ConcurrentHashMap<String, Long>()
    private val cachedReplies = ConcurrentHashMap<String, ByteArray>()
    private val inFlight = AtomicInteger()
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var boundNetwork: Network? = null
    @Volatile private var requested = false
    @Volatile private var localCandidates: List<S5DirectCandidatePolicy.Candidate> = emptyList()
    @Volatile private var lastDiagnostic = "未启动"
    @Volatile private var lastFailureReason: String? = null
    @Volatile private var lastCandidateRefreshAtMillis = 0L
    @Volatile private var lastSuccessfulCommunicationAtMillis = 0L
    @Volatile private var generation = 0L
    private var socketRetryAttempt = 0
    private var socketRetryFuture: ScheduledFuture<*>? = null
    private var gatherFuture: ScheduledFuture<*>? = null

    data class DirectReply(val bytes: ByteArray, val endpoint: InetSocketAddress)
    private data class Pending(val fingerprint: String, val expiration: Long,
        val answer: CompletableFuture<DirectReply>)
    private data class StunPending(val expected: InetSocketAddress,
        val transaction: ByteArray, val answer: CompletableFuture<S5StunCodec.MappedEndpoint>)
    private data class Route(val destination: InetSocketAddress, val verifiedAt: Long)

    val active: Boolean get() = socket?.isClosed == false
    val status: String get() = when {
        !requested -> "未授权持续UDP监听"
        !active -> "持续监听已获授权，等待系统默认网络；" + lastDiagnostic
        else -> "UDP端口" + (socket?.localPort ?: 0) +
            "；本机候选" + localCandidates.size + "个；对端候选" +
            peerCandidates.values.sumOf { it.size } + "个；已验签直连设备" +
            verifiedRoutes.count { verified(it.key) } + "个；最近候选刷新" +
            lastCandidateRefreshAtMillis + "；最近成功通信" +
            lastSuccessfulCommunicationAtMillis + "；" + lastDiagnostic
    }

    fun verified(peer: String): Boolean {
        val route = verifiedRoutes[peer] ?: return false
        return active && System.currentTimeMillis() - route.verifiedAt in 0..120_000L
    }

    fun hasCandidates(peer: String): Boolean = active &&
        (verified(peer) || peerCandidates[peer].orEmpty().any {
            S5DirectCandidatePolicy.fresh(it, System.currentTimeMillis())
        })

    @Synchronized fun start() {
        requested = true
        if (active) return
        localIdentity
        openSocketForCurrentNetwork()
    }

    @Synchronized fun stop() {
        requested = false
        socketRetryFuture?.cancel(false)
        socketRetryFuture = null
        releaseTransport("UDP持续监听授权已关闭")
        lastDiagnostic = "已停止，本机UDP端口关闭"
        onChanged()
    }

    private fun openSocketForCurrentNetwork() {
        if (!requested || active) return
        val network = connectivity.activeNetwork
        if (network == null) {
            boundNetwork = null
            lastFailureReason = "NO_DEFAULT_NETWORK"
            lastDiagnostic = "持续监听授权已保存；当前没有默认网络，等待网络恢复"
            onChanged()
            return
        }
        generation++
        val current = generation
        val created = DatagramSocket(null)
        try {
            created.reuseAddress = false
            created.bind(InetSocketAddress(0))
            network.bindSocket(created)
            created.soTimeout = 1500
        } catch (error: Exception) {
            runCatching { created.close() }
            socket = null
            boundNetwork = null
            lastFailureReason = error.message ?: error.javaClass.simpleName
            lastDiagnostic = "无法将UDP监听绑定到当前网络：" + lastFailureReason
            onChanged()
            scheduleSocketRetry()
            return
        }
        socket = created
        boundNetwork = network
        socketRetryAttempt = 0
        socketRetryFuture?.cancel(false)
        socketRetryFuture = null
        lastFailureReason = null
        lastDiagnostic = "UDP端口已绑定当前系统网络，正在刷新IPv4/IPv6候选"
        Thread({ receiveLoop(created, current) }, "okg-s5-udp-receive").apply {
            isDaemon = true
            start()
        }
        scheduleGather(current, 0)
        onChanged()
    }

    private fun scheduleSocketRetry() {
        if (!requested || connectivity.activeNetwork == null) return
        socketRetryFuture?.cancel(false)
        val delay = NetworkReconnectPolicy.delayMillis(
            socketRetryAttempt++, ThreadLocalRandom.current().nextDouble())
        socketRetryFuture = scheduler.schedule({
            synchronized(this) {
                socketRetryFuture = null
                if (requested && !active) openSocketForCurrentNetwork()
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun networkChanged() {
        if (!requested) return
        socketRetryFuture?.cancel(false)
        socketRetryFuture = null
        releaseTransport("Android网络变化，旧UDP映射和直连路径已失效")
        val network = connectivity.activeNetwork
        if (network == null) {
            lastDiagnostic = "持续监听仍获授权，但当前无默认网络；等待Wi-Fi或移动数据恢复"
            lastFailureReason = "NO_DEFAULT_NETWORK"
            onChanged()
            return
        }
        openSocketForCurrentNetwork()
        if (active) lastDiagnostic = "网络或地址变化，UDP Socket已重新绑定并刷新STUN候选"
        onChanged()
    }

    private fun releaseTransport(reason: String) {
        generation++
        gatherFuture?.cancel(false)
        gatherFuture = null
        socket?.close()
        socket = null
        boundNetwork = null
        localCandidates = emptyList()
        peerCandidates.clear()
        verifiedRoutes.clear()
        publishAt.clear()
        seenRequests.clear()
        cachedReplies.clear()
        pending.values.forEach { it.answer.completeExceptionally(IOException(reason)) }
        pending.clear()
        stun.values.forEach { it.answer.completeExceptionally(IOException(reason)) }
        stun.clear()
        lastFailureReason = reason
        lastCandidateRefreshAtMillis = 0L
    }

    fun onContact(peer: String) {
        if (active) scheduler.execute { publishTo(peer, force = true) }
    }

    fun onSignalReady() {
        if (active) scheduler.execute { publishAll() }
    }

    fun onCandidates(peer: String, candidates: List<S5DirectCandidatePolicy.Candidate>) {
        if (!active || pairings.snapshot().peers.none {
            it.active && it.peerFingerprint == peer
        }) return
        val now = System.currentTimeMillis()
        peerCandidates[peer] = candidates.filter {
            S5DirectCandidatePolicy.fresh(it, now)
        }.take(8)
        lastDiagnostic = "收到" + peer.take(8) + "的加密候选，正在尝试连通性检查"
        onChanged()
        requestProbe()
    }

    /** Returns raw bytes for the existing pinned-key reply verifier. */
    fun request(peer: String, signedFrame: ByteArray, timeoutMillis: Int): DirectReply {
        require(signedFrame.size in 100..6800) { "应用数据过大，实验性UDP路径拒绝发送" }
        val live = socket?.takeUnless { it.isClosed }
            ?: throw IOException("未启用持续UDP直连监听")
        val contact = signal.verifiedContact(peer)
            ?: throw IOException("尚无已绑定设备的可信Nostr联系卡")
        val initial = destinations(peer)
        if (initial.isEmpty()) throw IOException("未获得对端新鲜UDP候选")
        if (inFlight.incrementAndGet() > 8) {
            inFlight.decrementAndGet()
            throw IOException("直连请求过多，已保护本机")
        }
        val requestId = UUID.randomUUID().toString()
        val expiry = System.currentTimeMillis() + timeoutMillis.coerceIn(2000, 20_000) + 3000
        val response = CompletableFuture<DirectReply>()
        pending[requestId] = Pending(peer, expiry, response)
        try {
            val bytes = sealPacket("req", requestId, expiry, peer,
                signedFrame, contact.encryptionPublic)
            if (bytes.size > 10_500) throw IOException("UDP数据包超过实验上限")
            val deadline = System.currentTimeMillis() + timeoutMillis
            var burst = 0
            while (System.currentTimeMillis() < deadline) {
                val options = destinations(peer).ifEmpty { initial }
                for (target in options.take(8)) send(live, target, bytes)
                burst++
                try {
                    val wait = minOf(900L, (deadline - System.currentTimeMillis()).coerceAtLeast(1))
                    return response.get(wait, TimeUnit.MILLISECONDS)
                } catch (_: java.util.concurrent.TimeoutException) {
                    if (burst % 3 == 0) requestProbe()
                }
            }
            lastFailureReason = "UDP_DIRECT_TIMEOUT"
            lastDiagnostic = "UDP握手超时：候选存在，但没有双向加密回包；可能是CGNAT或防火墙"
            onChanged()
            throw SocketTimeoutException(lastDiagnostic)
        } catch (error: java.util.concurrent.ExecutionException) {
            throw IOException("直连监听中断：${error.cause?.message ?: "未知"}")
        } finally {
            pending.remove(requestId)
            inFlight.decrementAndGet()
        }
    }

    /** Called ONLY after S1GuardManager verifies the signed application receipt. */
    fun confirmSignedReply(peer: String, source: InetSocketAddress) {
        verifiedRoutes[peer] = Route(source, System.currentTimeMillis())
        lastSuccessfulCommunicationAtMillis = System.currentTimeMillis()
        lastFailureReason = null
        lastDiagnostic = peer.take(8) + " 已完成UDP双向收发和应用签名回执校验"
        onChanged()
    }

    private fun destinations(peer: String): List<InetSocketAddress> {
        val now = System.currentTimeMillis()
        val preferred = verifiedRoutes[peer]?.takeIf { verified(peer) }?.destination
        val others = peerCandidates[peer].orEmpty().filter {
            S5DirectCandidatePolicy.fresh(it, now)
        }.mapNotNull {
            runCatching { InetSocketAddress(InetAddress.getByName(it.ip), it.port) }.getOrNull()
        }
        return (listOfNotNull(preferred) + others).distinct().take(8)
    }

    private fun sealPacket(kind: String, id: String, expires: Long, target: String,
                           plaintext: ByteArray, public: String): ByteArray {
        val aad = crypto.associated(target, id, "s5-udp-$kind", expires)
        val boxed = crypto.seal(plaintext, public, aad)
        return JSONObject().put("v", 1).put("kind", kind).put("id", id)
            .put("from", ownFingerprint).put("to", target).put("exp", expires)
            .put("box", boxed.json()).toString().toByteArray(Charsets.UTF_8)
    }

    private fun receiveLoop(live: DatagramSocket, current: Long) {
        while (!live.isClosed && current == generation) {
            val packet = DatagramPacket(ByteArray(12_000), 12_000)
            try {
                live.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                if (bytes.size >= 20 && bytes[0] == 1.toByte() && bytes[1] == 1.toByte()) {
                    deliverStun(bytes, packet.address, packet.port)
                    continue
                }
                if (bytes.size !in 100..10_500) continue
                val from = InetSocketAddress(packet.address, packet.port)
                try { work.execute { handle(bytes, from, current) } }
                catch (_: java.util.concurrent.RejectedExecutionException) {
                    lastDiagnostic = "收到过多UDP报文，已限制并发"
                }
            } catch (_: SocketTimeoutException) {
                // Allows lifecycle checks without changing the persistent port.
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                // Untrusted UDP input never crashes the receiver.
            }
        }
    }

    private fun handle(bytes: ByteArray, source: InetSocketAddress, current: Long) {
        if (current != generation || !active) return
        try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            if (json.getInt("v") != 1 || json.getString("to") != ownFingerprint) return
            val kind = json.getString("kind")
            if (kind != "req" && kind != "resp") return
            val sender = json.getString("from")
            if (sender.length != 64 || !sender.matches(Regex("[0-9a-f]{64}"))) return
            if (pairings.snapshot().peers.none {
                it.active && it.peerFingerprint == sender
            }) return
            val contact = signal.verifiedContact(sender) ?: return
            val id = json.getString("id")
            if (id.length != 36) return
            val expiry = json.getLong("exp")
            val now = System.currentTimeMillis()
            if (expiry !in now..(now + 25_000L)) return
            val payload = crypto.open(S2PublicNostrCrypto.Sealed.parse(json.getJSONObject("box")),
                localIdentity.agreementPrivate,
                crypto.associated(ownFingerprint, id, "s5-udp-$kind", expiry))
            if (kind == "resp") {
                val awaited = pending[id] ?: return
                if (awaited.fingerprint != sender || awaited.expiration != expiry) return
                awaited.answer.complete(DirectReply(payload, source))
                return
            }
            val dedupKey = "$sender|$id"
            if (seenRequests.putIfAbsent(dedupKey, expiry) != null) {
                // A NAT-dropped ACK must not make retry permanently impossible.
                cachedReplies[dedupKey]?.let { socket?.let { s -> send(s, source, it) } }
                return
            }
            if (payload.size !in 100..6800) return
            // Verify the untrusted outer sender against the signed envelope.
            val claimed = LanTestWire.decode(payload).message.senderDeviceId.value
            if (claimed != "okg-$sender") return
            val signedReply = receiveSigned(payload)
            if (current != generation || !active) return
            val response = sealPacket("resp", id, expiry, sender, signedReply,
                contact.encryptionPublic)
            if (response.size <= 10_500) {
                cachedReplies[dedupKey] = response
                socket?.let { send(it, source, response) }
            }
        } catch (_: Exception) {
            // Bad signatures, GCM tags and unsolicited requests fail closed.
        }
    }

    private fun scheduleGather(current: Long, delaySeconds: Long) {
        gatherFuture?.cancel(false)
        gatherFuture = scheduler.schedule({
            if (current != generation || !active) return@schedule
            runCatching { gather(current) }.onFailure {
                lastDiagnostic = "公网候选采集失败：" + it.javaClass.simpleName
                lastFailureReason = it.message ?: it.javaClass.simpleName
                onChanged()
            }
            if (current == generation && active) scheduleGather(current, 60)
        }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun gather(current: Long) {
        val live = socket ?: return
        val network = boundNetwork ?: return
        val expiry = System.currentTimeMillis() + 110_000L
        val result = mutableListOf<S5DirectCandidatePolicy.Candidate>()

        // Only advertise global IPv6 addresses belonging to the socket's bound
        // Android Network; do not leak addresses from inactive Wi-Fi/cellular links.
        runCatching {
            connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
                .map { it.address }
                .filterIsInstance<Inet6Address>()
                .filter { !it.isAnyLocalAddress && !it.isLinkLocalAddress &&
                    !it.isLoopbackAddress && !it.isSiteLocalAddress &&
                    !it.isMulticastAddress && it.scopeId == 0 }
                .forEach { address ->
                    S5DirectCandidatePolicy.accept(address.hostAddress.orEmpty(),
                        live.localPort, expiry, System.currentTimeMillis())?.let(result::add)
                }
        }

        // Probe both address families when a STUN host publishes A and AAAA records.
        // Send all requests first, then collect them within one bounded window.
        val targets = listOf("stun.miwifi.com", "stun.hitv.com", "stun.qq.com")
            .flatMap { domain ->
                runCatching { InetAddress.getAllByName(domain).toList() }
                    .getOrDefault(emptyList())
                    .let { addresses ->
                        listOfNotNull(
                            addresses.firstOrNull { it is Inet4Address },
                            addresses.firstOrNull { it is Inet6Address }
                        ).map { InetSocketAddress(it, 3478) }
                    }
            }.distinctBy { it.address to it.port }.take(6)

        val probes = mutableListOf<Pair<String, CompletableFuture<S5StunCodec.MappedEndpoint>>>()
        targets.forEach { remote ->
            if (current != generation || live.isClosed) return
            val transaction = ByteArray(12).also(random::nextBytes)
            val key = transaction.joinToString("") { "%02x".format(it.toInt() and 255) }
            val future = CompletableFuture<S5StunCodec.MappedEndpoint>()
            stun[key] = StunPending(remote, transaction, future)
            probes += key to future
            send(live, remote, S5StunCodec.bindingRequest(transaction))
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1600L)
        probes.forEach { (key, future) ->
            try {
                val remaining = deadline - System.nanoTime()
                if (remaining > 0L) {
                    val mapped = future.get(remaining, TimeUnit.NANOSECONDS)
                    S5DirectCandidatePolicy.accept(mapped.address, mapped.port, expiry,
                        System.currentTimeMillis())?.let(result::add)
                }
            } catch (_: Exception) {
                // Keep other IPv4/IPv6 candidates; never invent an external mapping.
            } finally {
                stun.remove(key)
            }
        }
        if (current != generation) return
        localCandidates = result.distinctBy { it.ip to it.port }.take(8)
        lastCandidateRefreshAtMillis = System.currentTimeMillis()
        lastFailureReason = if (localCandidates.isEmpty()) "STUN_NO_CANDIDATES" else null
        lastDiagnostic = if (localCandidates.isEmpty())
            "UDP监听正常，但IPv4/IPv6 STUN均未返回可用候选；检查UDP/3478"
        else "获得" + localCandidates.size + "个新鲜IPv4/IPv6候选，等待双方握手"
        onChanged()
        publishAll()
        val cleanupNow = System.currentTimeMillis()
        seenRequests.entries.removeIf { it.value < cleanupNow }
        cachedReplies.keys.retainAll(seenRequests.keys)
    }

    private fun deliverStun(bytes: ByteArray, address: InetAddress, port: Int) {
        for ((_, waiting) in stun) {
            if (waiting.expected.address != address || waiting.expected.port != port) continue
            val mapped = S5StunCodec.mappedAddress(bytes, waiting.transaction) ?: continue
            waiting.answer.complete(mapped)
            return
        }
    }

    private fun publishAll() {
        pairings.snapshot().peers.filter { it.active }.map { it.peerFingerprint }
            .distinct().forEach { publishTo(it, force = false) }
    }

    private fun publishTo(peer: String, force: Boolean) {
        if (!active || localCandidates.isEmpty()) return
        val last = publishAt[peer] ?: 0L
        val now = System.currentTimeMillis()
        if (!force && now - last < 25_000L) return
        if (signal.publishCandidates(peer, localCandidates)) {
            publishAt[peer] = now
            lastDiagnostic = "加密候选已通过WebSocket排队发送，尚待对端确认收到"
            onChanged()
        }
    }

    private fun send(live: DatagramSocket, address: InetSocketAddress, bytes: ByteArray) {
        if (live.isClosed || address.isUnresolved) return
        try { live.send(DatagramPacket(bytes, bytes.size, address)) }
        catch (_: IOException) { /* Other candidate routes may work. */ }
    }
}
