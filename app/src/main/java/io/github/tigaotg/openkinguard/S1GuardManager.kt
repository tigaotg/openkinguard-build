package io.github.tigaotg.openkinguard

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import io.github.tigaotg.openkinguard.core.engine.CommandFailureExplainer
import io.github.tigaotg.openkinguard.core.engine.RemoteLocationDenied
import io.github.tigaotg.openkinguard.core.engine.RemoteLocationDenial
import io.github.tigaotg.openkinguard.core.model.*
import io.github.tigaotg.openkinguard.security.protocol.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.json.JSONArray

data class DiscoveredNode(val serviceName: String, val address: String, val port: Int,
    val advertisedNickname: String = "")
data class LanHelpOffer(val address: String, val requestId: String, val nickname: String,
    val incoming: Boolean, val expiresAt: Long)

/** Human approval is required once; remaining signed LAN frames complete in the background. */
internal object LanSingleApprovalPolicy {
    fun autoConfirm(phase: PairingPhase?, ownPendingOfferMatches: Boolean): Boolean =
        (phase == PairingPhase.REVIEW_INVITE && ownPendingOfferMatches) ||
            phase == PairingPhase.REVIEW_GRANT
}
data class ExerciseUiState(
    val listening: Boolean,
    val nodes: List<DiscoveredNode>,
    val verifiedRelations: Set<String>,
    val verifiedNodeServices: Map<String, String>,
    val discoveryError: String,
    val diagnostics: List<String>,
    val history: ExerciseState,
    /** Included to trigger Compose recomposition when WSS connects/disconnects. */
    val relayStatus: String,
    val relayConnected: Boolean,
    val relayVerifiedRelations: Set<String>,
    val directVerifiedRelations: Set<String>,
)

/** S1/S2 LAN transport: Android NSD + pinned-key signed TCP; lifecycle owned by S1Runtime. */
class S1GuardManager(context: Context) {
    private val app = context.applicationContext
    private val pairings = LocalPairingManager(app)
    private val keys = AndroidDeviceKeyStore()
    private val store = S1CaseStore(app)
    private val gps = S3GpsCapture(app)
    private val relay = S2RelayPool(app, keys, { signal() }, { forwardRelayInbound(it) },
        { slot, sender, signed -> receiveFamilyPairing(slot, sender, signed) },
        { slot, sender, session -> receiveFamilyPairingCancel(slot, sender, session) },
        { frame -> handleLateRelayReply(frame) })
    private val publicNostr = S2NostrTransport(app, keys, pairings, { signal() })
    private val remotePairing = RemotePairingManager(app, keys, pairings, publicNostr,
        { signal() })
    private val direct = S5DirectUdp(app, keys, pairings, publicNostr,
        { forwardRelayInbound(it) }, { signal() }, { probeAsync() })
    // Isolated, in-memory consent state. No legacy alert, pairing or location data changes.
    private val remoteAssistConsent = RemoteAssistConsent()
    @Volatile private var remoteRtc: RemoteAssistWebRtc? = null
    @Volatile private var remoteRtcSessionId = ""
    @Volatile private var monitoredAssistSessionId = ""
    private data class AssistExitNotice(val sessionId: String, val message: String)
    @Volatile private var lastAssistExitNotice: AssistExitNotice? = null
    @Volatile private var pendingRemoteRenderer: org.webrtc.SurfaceViewRenderer? = null
    private val rtcSignalAssembler = RemoteAssistRtcSignalAssembler()
    @Volatile private var remotePeerDiagnosticSession = ""
    @Volatile private var remotePeerDiagnostics = ""
    @Volatile private var remotePeerDiagnosticAt = 0L
    private val remoteStreamSequence = AtomicLong(0)
    private val remoteStreamReplay = ConcurrentHashMap<String, ReplayWindow>()
    private val relayVerifiedAt = ConcurrentHashMap<String, Long>()
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val nodes = ConcurrentHashMap<String, DiscoveredNode>()
    private val verified = ConcurrentHashMap<String, String>() // relation ID -> mDNS service name
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)
    private val probingLock = Any()
    @Volatile private var probingCompletion: CompletableFuture<Unit>? = null
    private var browseListener: NsdManager.DiscoveryListener? = null
    private var advertiseListener: NsdManager.RegistrationListener? = null
    private var advertiseRefreshPending = false
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var ownServiceName = "OKG-" + UUID.randomUUID().toString().take(12)
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var listenerAcceptThread: Thread? = null
    @Volatile private var listenerLoopRunning = false
    @Volatile private var listenerUnexpectedlyClosed = false
    @Volatile private var listenerFailureCode: String? = null
    @Volatile private var changed: (() -> Unit)? = null
    @Volatile private var discoveryError = ""
    @Volatile private var lanPairingAddress: String? = null
    @Volatile private var familyPairingPeer: FamilyRelayPeer? = pairings.snapshot().familyRelayPeer
    @Volatile private var familyDiscoveredPeers: List<FamilyRelayPeer> = emptyList()
    @Volatile private var lanPairingToken: String? = null
    @Volatile private var lanPairingSessionId: String? = null
    @Volatile private var lanPairingRelationId: String? = null
    @Volatile private var helpOffer: LanHelpOffer? = null
    @Volatile private var lastRemoteCancellation: Triple<String, String, Long>? = null
    private val closedOffers = ConcurrentHashMap<String, Long>()
    private val pairingGuard = Any()
    @Volatile private var inboundWorkers: ThreadPoolExecutor? = null
    private val diagnosticLog = ArrayDeque<String>()

    init {
        publicNostr.directCandidates = { fp, items -> direct.onCandidates(fp, items) }
        publicNostr.directContact = { fp -> direct.onContact(fp) }
        publicNostr.directReady = { direct.onSignalReady() }
        publicNostr.directNetworkChanged = { direct.networkChanged() }
        publicNostr.remotePairingInbound = { packet ->
            runCatching { remotePairing.receive(packet) }.onFailure { error ->
                diagnostic("远程邀请消息", error.message ?: "无效或过期的配对消息")
            }
        }
    }

    companion object {
        private const val SERVICE = "_openkinguard._tcp."
        private const val MAX_FOREGROUND_ATTEMPTS = 6
        private const val LAN_PAIR_PREFIX = "OKG-LAN-PAIR-1:"
        private const val LAN_PAIR_ACCEPTED = "OKG-LAN-PAIR-1:ACCEPTED"
    }

    fun snapshot() = ExerciseUiState(listener?.isClosed == false, nodes.values.sortedBy { it.serviceName },
        verified.keys.toSet(), verified.toMap(), discoveryError, synchronized(diagnosticLog) {
            diagnosticLog.toList()
        }, store.snapshot(), relayStatus(), relayConnected(), relayVerifiedAt.keys.filter {
            verifiedByRelay(it)
        }.toSet(), relations().filter { direct.verified(it.peerFingerprint) }
            .map { it.relationId }.toSet())

    private fun diagnostic(stage: String, reason: String) {
        val entry = "$stage：${reason.take(180)}"
        runCatching { store.recordHandshake("诊断", stage, "", "异常", reason) }
        synchronized(diagnosticLog) {
            if (diagnosticLog.lastOrNull() != entry) {
                diagnosticLog.addLast(entry)
                while (diagnosticLog.size > 12) diagnosticLog.removeFirst()
            }
        }
        signal()
    }

    fun relations() = pairings.snapshot().peers.filter { it.active }

    fun relayStatus(): String = relay.status + "；" + publicNostr.status + "；" + direct.status
    // S5: Nostr is signaling-only; it must NEVER qualify as a business relay.
    fun relayConnected(): Boolean = relay.connected
    internal fun privateRelayDiagnostics(): List<PrivateRelayNodeDiagnostic> = relay.diagnostics()
    internal fun localTcpListenerStatus(): String = tcpListenerHealth().displayLabel

    internal fun connectionPeerDiagnostics(): List<PeerConnectionDiagnostic> {
        val history = store.snapshot()
        return relations().map { peer ->
            val nickname = history.peerNicknames[peer.relationId].orEmpty().ifBlank {
                history.verifiedPeerNicknames[peer.relationId].orEmpty()
            }.ifBlank {
                history.verifiedDeviceNicknames[peer.peerFingerprint].orEmpty()
            }.ifBlank { "设备 ${peer.peerFingerprint.take(8)}" }
            PeerConnectionDiagnostic(
                relationId = peer.relationId,
                displayName = nickname.take(32),
                roleDescription = if (peer.role == PairingRole.SUBJECT)
                    "对方是我的救助者" else "我正在帮助对方",
                lanVerified = verified.containsKey(peer.relationId),
                relayVerified = verifiedByRelay(peer.relationId),
                directVerified = direct.verified(peer.peerFingerprint),
                lastSignedMessageAtMillis = history.lastVerifiedAt[peer.relationId] ?: 0L
            )
        }.sortedBy { it.displayName }
    }

    fun familyRelayReady(): Boolean = relay.familyReady
    fun familyRelayPeers(): List<FamilyRelayPeer> = familyDiscoveredPeers
    fun canContinueFamilyPairing(): Boolean = familyPairingPeer != null

    /** Discovery does not create a helping relationship or grant any data access. */
    fun discoverFamilyRelayPeers(): Int {
        val fresh = relay.familyPeers().filter { peer ->
            peer.fingerprint != keys.createOrLoad().fingerprint
        }
        familyDiscoveredPeers = fresh
        signal()
        return fresh.size
    }

    fun inviteOnFamilyRelay(peer: FamilyRelayPeer) {
        require(familyDiscoveredPeers.contains(peer)) { "设备列表已变化，请重新查找" }
        require(relay.familyReady) { "家庭中继尚未连接" }
        require(relations().none { it.peerFingerprint == peer.fingerprint &&
            it.role == PairingRole.SUBJECT }) { "已建立同一方向的帮助关系" }
        val invitation: String
        synchronized(pairingGuard) {
            require(pairings.snapshot().session == null && lanPairingAddress == null &&
                familyPairingPeer == null && remotePairing.current() == null) {
                "请先结束当前邀请"
            }
            invitation = requireNotNull(pairings.createInvitation(peer).session).invite
            familyPairingPeer = peer
        }
        signal()
        sendFamilyPairing(invitation)
    }

    private fun receiveFamilyPairing(slot: Int, sender: String, signed: String): Boolean {
        try {
            val frame = PairingQrProtocol.decodeAndVerify(signed, System.currentTimeMillis()).frame
            require(frame.senderFingerprint == sender) { "中继声称的发送者与签名不一致" }
            val phase: PairingPhase?
            synchronized(pairingGuard) {
                val peer = familyPairingPeer
                val session = pairings.snapshot().session
                if (peer == null) {
                    require(lanPairingAddress == null && remotePairing.current() == null &&
                        pendingHelpOffer() == null) { "正在处理其他绑定" }
                    if (session == null) require(frame.step == PairingStep.INVITE) {
                        "当前没有可恢复的家庭绑定会话"
                    }
                    require(relay.familyReady) { "家庭中继已断开" }
                } else {
                    require(peer.fingerprint == sender && session != null) {
                        "请求来自其他设备或中继"
                    }
                }
                val currentPeer = FamilyRelayPeer(sender, slot)
                val saved = pairings.scan(signed, currentPeer)
                familyPairingPeer = if (saved.session?.phase == PairingPhase.COMPLETE) null else currentPeer
                phase = saved.session?.phase
            }
            store.recordHandshake("同节点", frame.step.name, frame.relationId, "已验签",
                "家庭中继转发签名绑定帧；仍需对方同意和8位安全码核对")
            if (phase == PairingPhase.REVIEW_INVITE) {
                runCatching { S1TestNotifier.showFamilyInvitation(app, sender) }
            }
            signal()
            if (phase == PairingPhase.REVIEW_GRANT) {
                // The helper previously approved INVITE. The final signed receipt
                // is now generated after the inbound frame has been acknowledged.
                Thread({
                    runCatching { confirmFamilyPairing() }.onFailure { error ->
                        diagnostic("同节点自动签名", error.message ?: "发送失败，可手动重试")
                    }
                }, "okg-family-pair-ack").apply { isDaemon = true; start() }
            }
            return true
        } catch (error: Exception) {
            diagnostic("同节点绑定", error.message ?: "无效签名或会话冲突")
            return false
        }
    }

    fun confirmFamilyPairing() {
        require(familyPairingPeer != null) { "家庭中继绑定会话已失效" }
        val updated = pairings.confirm().session ?: error("没有待确认的绑定")
        val frame = when (updated.phase) {
            PairingPhase.WAIT_GRANT -> updated.response
            PairingPhase.WAIT_ACK -> updated.grant
            PairingPhase.ACK_TO_SHOW -> updated.ack
            else -> error("当前步骤不支持确认")
        }
        sendFamilyPairing(frame)
        if (updated.phase == PairingPhase.ACK_TO_SHOW) {
            pairings.finishHelperAfterAckShown()
            familyPairingPeer = null
        }
        signal()
    }

    fun retryFamilyPairing() {
        val session = requireNotNull(pairings.snapshot().session) { "没有待重发的消息" }
        val frame = when (session.phase) {
            PairingPhase.WAIT_RESPONSE -> session.invite
            PairingPhase.WAIT_GRANT -> session.response
            PairingPhase.WAIT_ACK -> session.grant
            PairingPhase.ACK_TO_SHOW -> session.ack
            else -> error("现在无需重发")
        }
        sendFamilyPairing(frame)
        if (session.phase == PairingPhase.ACK_TO_SHOW) {
            pairings.finishHelperAfterAckShown()
            familyPairingPeer = null
        }
        signal()
    }

    fun cancelFamilyPairing() {
        val peer = familyPairingPeer
        val sessionId = pairings.snapshot().session?.let { session ->
            runCatching { PairingQrProtocol.decodeAndVerify(session.invite,
                System.currentTimeMillis()).frame.sessionId }.getOrNull()
        }
        synchronized(pairingGuard) {
            pairings.cancelPending()
            familyPairingPeer = null
        }
        if (peer != null && sessionId != null) {
            val currentPeer = runCatching { relay.resolveFamilyPeer(peer.fingerprint) }.getOrNull() ?: peer
            relay.cancelPairing(currentPeer, sessionId)
        }
        signal()
    }

    private fun receiveFamilyPairingCancel(slot: Int, sender: String, sessionId: String) {
        synchronized(pairingGuard) {
            val peer = familyPairingPeer ?: return
            val session = pairings.snapshot().session ?: return
            if (peer.fingerprint != sender || session.phase == PairingPhase.COMPLETE) return
            val expected = runCatching { PairingQrProtocol.decodeAndVerify(session.invite,
                System.currentTimeMillis()).frame.sessionId }.getOrNull()
            if (sessionId != expected) return
            pairings.cancelPending()
            familyPairingPeer = null
        }
        store.recordHandshake("同节点", "CANCEL", "", "对方取消", "对端结束未完成的家庭绑定")
        signal()
    }

    private fun sendFamilyPairing(frame: String) {
        val previous = requireNotNull(familyPairingPeer) { "同节点设备已失联" }
        val peer = relay.resolveFamilyPeer(previous.fingerprint)
        familyPairingPeer = peer
        pairings.setFamilyRelayPeer(peer)
        // Re-use exactly the same signed frame on retries. A relay transport
        // receipt only proves receipt; it never authorizes a helping relationship.
        relay.sendPairing(peer, frame)
        signal()
    }
    /** Independent diagnostics so a private relay cannot mask public-node failures. */
    fun publicRelayStatus(): String = publicNostr.status
    fun publicRelayReady(): Boolean = publicNostr.connected
    internal fun remotePairingSession(): RemotePairingManager.Session? =
        remotePairing.current()
    fun createRemotePairingInvite(): String =
        remotePairing.createInvite(store.snapshot().nostrRelayEndpoints)
    fun acceptRemotePairingInvite(link: String): String =
        remotePairing.acceptInvite(link, store.snapshot().nostrRelayEndpoints)
    fun confirmRemotePairing(): String = remotePairing.approve()
    fun retryRemotePairing(): String = remotePairing.retry()
    fun cancelRemotePairing() = remotePairing.cancel()
    fun finishRemotePairing() = remotePairing.finish()
    fun directStatus(): String = direct.status
    fun directActive(): Boolean = direct.active
    fun directVerified(relationId: String): Boolean =
        relations().any { it.relationId == relationId && direct.verified(it.peerFingerprint) }

    /** Explicit opt-in for this run; no surprise background UDP listener on upgrade. */
    fun startPublicDirectTest() {
        require(listener?.isClosed == false) { "请先启动本机守护监听" }
        require(store.snapshot().nostrRelayEnabled) { "请先开启公共信令并等待节点确认" }
        direct.start()
        signal()
    }

    fun directListenerAuthorizationEnabled(): Boolean =
        store.snapshot().directUdpListenerEnabled

    /** Independent persistent opt-in; never inferred from public Nostr consent. */
    fun configureDirectListenerAuthorization(enabled: Boolean) {
        if (enabled) {
            require(listener?.isClosed == false) { "请先启动本机守护监听" }
            require(store.snapshot().nostrRelayEnabled) { "请先开启公共信令" }
        }
        store.update { state ->
            state.copy(directUdpListenerEnabled = enabled) to Unit
        }
        if (enabled) direct.start() else direct.stop()
        signal()
    }

    fun stopPublicDirectTest() {
        // A one-shot test button cannot revoke a separately granted persistent listener.
        if (!store.snapshot().directUdpListenerEnabled) direct.stop()
        signal()
    }

    /** Session authorization is volatile and independent of all SOS/pairing state. */
    internal fun remoteAssistSession(): RemoteAssistConsent.Session? = remoteAssistConsent.snapshot()

    internal fun remoteAssistExitMessage(sessionId: String? = null): String? =
        lastAssistExitNotice?.takeIf { sessionId == null || it.sessionId == sessionId }?.message

    internal fun dismissRemoteAssistExitMessage() {
        lastAssistExitNotice = null
        signal()
    }

    internal fun remoteAssistRtcSize(): Pair<Int, Int>? = remoteRtc?.videoSize()
    internal fun remoteAssistRtcFresh(): Boolean = remoteRtc?.fresh() == true

    internal fun remoteAssistDiagnosticReport(): String {
        val session = remoteAssistConsent.snapshot()
        val matching = session != null && session.sessionId == remotePeerDiagnosticSession
        val age = if (remotePeerDiagnosticAt == 0L || !matching) "尚未收到" else
            "${(System.currentTimeMillis() - remotePeerDiagnosticAt) / 1000}秒前"
        return RemoteAssistDiagnostics.report(AppVersionInfo.name(app)) +
            "\n\n【视频传输】\n使用开源 WebRTC 的 ICE + DTLS/SRTP；" +
            "音频已禁用，流媒体不经过 SOS/绑定信令服务。\n" +
            "\n【被控制端自动回传（$age）】\n" +
            (if (matching) remotePeerDiagnostics else "").ifBlank {
                "尚未收到。共享开始10秒后点刷新，或查看被控制端诊断。"
            }
    }

    internal fun sendRemoteAssistDiagnostic(sessionId: String, report: String) {
        require(authorizedRemoteControl(sessionId)) { "屏幕共享已结束" }
        val session = requireNotNull(remoteAssistConsent.snapshot())
        val peer = relations().singleOrNull { it.active &&
            it.relationId == session.relationId &&
            it.peerFingerprint == session.peerFingerprint && it.role == PairingRole.SUBJECT }
            ?: error("帮助关系已失效")
        sendRemoteAssistPacket(peer, RemoteAssistStreamWire.DIAGNOSTIC, sessionId,
            RemoteAssistStreamWire.diagnostic(peer.relationId, sessionId, report))
    }

    private fun sendRtcSignal(sessionId: String, type: String, text: String) {
        val session = remoteAssistConsent.snapshot()
        require(session?.sessionId == sessionId &&
            session.phase in setOf(RemoteAssistConsent.Phase.AWAITING_SCREEN_PERMISSION,
                RemoteAssistConsent.Phase.ACTIVE)) { "当前授权已失效" }
        val peer = relations().singleOrNull { it.active &&
            it.relationId == session.relationId &&
            it.peerFingerprint == session.peerFingerprint } ?: error("帮助关系不存在")
        val content = text.toByteArray(Charsets.UTF_8)
        require(content.size in 1..38_000)
        val parts = content.asList().chunked(900)
        require(parts.size in 1..40)
        val signalId = UUID.randomUUID().toString()
        for ((index, part) in parts.withIndex()) {
            require(remoteAssistConsent.snapshot()?.sessionId == sessionId)
            val payload = RemoteAssistStreamWire.rtc(peer.relationId, sessionId,
                type, signalId, index, parts.size, part.toByteArray())
            sendRemoteAssistPacket(peer, RemoteAssistStreamWire.RTC, sessionId, payload)
        }
    }

    /** Engine is recreated for every explicitly approved session; no unattended restart. */
    @Synchronized private fun rtcFor(sessionId: String): RemoteAssistWebRtc {
        if (remoteRtcSessionId == sessionId) remoteRtc?.let { return it }
        remoteRtc?.close()
        rtcSignalAssembler.clear()
        remoteRtcSessionId = sessionId
        val rtc = RemoteAssistWebRtc(app, sessionId,
            allowed = {
                remoteAssistConsent.snapshot()?.let {
                    it.sessionId == sessionId && it.phase in
                        setOf(RemoteAssistConsent.Phase.AWAITING_SCREEN_PERMISSION,
                            RemoteAssistConsent.Phase.ACTIVE)
                } == true
            },
            sendSignal = { type, body -> sendRtcSignal(sessionId, type, body) },
            onFirstFrame = {
                if (remoteAssistConsent.remotePreviewStarted(sessionId))
                    startAssistLinkWatchdog(sessionId)
                signal()
            },
            onCaptureRevoked = {
                app.stopService(Intent(app, RemoteAssistProjectionService::class.java))
                Thread({ runCatching { stopRemoteAssist() } },
                    "okg-rtc-revoke").start()
            },
            onStatus = { state ->
                RemoteAssistProjectionService.setTransferStatus(state)
                signal()
            })
        remoteRtc = rtc
        pendingRemoteRenderer?.let { rtc.attachRenderer(it) }
        return rtc
    }

    internal fun screenShareStarted(sessionId: String): Boolean =
        remoteAssistConsent.screenStarted(sessionId).also {
            if (it) {
                startAssistLinkWatchdog(sessionId)
                signal()
            }
        }

    /** Poll only during a consented live session; local revocation always works offline. */
    @Synchronized private fun startAssistLinkWatchdog(sessionId: String) {
        if (monitoredAssistSessionId == sessionId) return
        monitoredAssistSessionId = sessionId
        Thread({
            val policy = RemoteAssistLinkLossPolicy()
            val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
            while (remoteAssistConsent.snapshot()?.let {
                    it.sessionId == sessionId && it.phase == RemoteAssistConsent.Phase.ACTIVE
                } == true) {
                val (connectedOnce, disconnected) =
                    remoteRtc?.takeIf { remoteRtcSessionId == sessionId }
                        ?.linkStatus() ?: (false to false)
                // A local-only Wi-Fi connection may have no default route.
                // An established, still-connected ICE path is stronger evidence.
                val hasNetwork = runCatching { connectivity.activeNetwork != null }
                    .getOrDefault(true) || (connectedOnce && !disconnected)
                when (policy.observe(System.currentTimeMillis(), hasNetwork,
                    connectedOnce, disconnected)) {
                    RemoteAssistLinkLoss.LOCAL_NETWORK -> {
                        endLostAssist(sessionId, "您已断网，远程协助已自动结束。请联网后重新申请。")
                        break
                    }
                    RemoteAssistLinkLoss.REMOTE_PEER -> {
                        endLostAssist(sessionId, "对方失去连接，远程协助已自动结束。请检查双方网络后重新申请。")
                        break
                    }
                    null -> Unit
                }
                try { Thread.sleep(1_000) }
                catch (_: InterruptedException) { break }
            }
            synchronized(this) {
                if (monitoredAssistSessionId == sessionId) monitoredAssistSessionId = ""
            }
        }, "okg-remote-link-watchdog").apply { isDaemon = true; start() }
    }

    @Synchronized private fun endLostAssist(sessionId: String, reason: String) {
        if (remoteAssistConsent.snapshot()?.sessionId != sessionId) return
        stopRemoteAssist(reason)
    }

    internal fun authorizedRemoteControl(sessionId: String): Boolean =
        remoteAssistConsent.snapshot()?.let { it.sessionId == sessionId &&
            it.direction == RemoteAssistConsent.Direction.INCOMING &&
            it.phase == RemoteAssistConsent.Phase.ACTIVE &&
            remoteAssistRequestsAllowed(it.relationId) } == true

    internal fun startRemoteRtcScreen(sessionId: String, resultData: Intent,
                                      width: Int, height: Int) {
        require(authorizedRemoteControl(sessionId)) { "未获得本次屏幕授权" }
        rtcFor(sessionId).startPublisher(resultData, width, height)
    }

    internal fun attachRemoteRtcRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        pendingRemoteRenderer = renderer
        remoteRtc?.attachRenderer(renderer)
    }

    internal fun detachRemoteRtcRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        if (pendingRemoteRenderer === renderer) pendingRemoteRenderer = null
        val rtc = remoteRtc
        if (rtc != null) rtc.detachRenderer(renderer) else renderer.release()
    }

    @Synchronized private fun closeRemoteRtc() {
        remoteRtc?.close()
        remoteRtc = null
        remoteRtcSessionId = ""
        rtcSignalAssembler.clear()
    }

    fun sendRemoteAssistTap(x: Int, y: Int): String = sendRemoteAssistGesture(
        RemoteAssistStreamWire.TAP, x, y)

    fun sendRemoteAssistSwipe(x1: Int, y1: Int, x2: Int, y2: Int,
                              durationMs: Int = 350): String =
        sendRemoteAssistGesture(RemoteAssistStreamWire.SWIPE, x1, y1, x2, y2,
            durationMs = durationMs)

    fun sendRemoteAssistRecents(): String =
        sendRemoteAssistGesture(RemoteAssistStreamWire.RECENTS)

    fun sendRemoteAssistBack(): String = sendRemoteAssistGesture(RemoteAssistStreamWire.BACK)
    fun sendRemoteAssistHome(): String = sendRemoteAssistGesture(RemoteAssistStreamWire.HOME)

    private fun sendRemoteAssistGesture(kind: String, x: Int = 0, y: Int = 0,
                                        x2: Int = 0, y2: Int = 0,
                                        durationMs: Int = 350): String {
        val session = remoteAssistConsent.snapshot()
            ?: throw IllegalStateException("远程协助会话已结束")
        require(session.direction == RemoteAssistConsent.Direction.OUTGOING &&
            session.phase == RemoteAssistConsent.Phase.ACTIVE &&
            remoteAssistRtcFresh()) {
            "必须等对方开启屏幕共享"
        }
        val peer = relations().singleOrNull { it.active && it.relationId == session.relationId &&
            it.peerFingerprint == session.peerFingerprint && it.role == PairingRole.HELPER }
            ?: throw IllegalStateException("原有帮助关系已撤销")
        val payload = when (kind) {
            RemoteAssistStreamWire.TAP ->
                RemoteAssistStreamWire.tap(peer.relationId, session.sessionId, x, y)
            RemoteAssistStreamWire.SWIPE ->
                RemoteAssistStreamWire.swipe(peer.relationId, session.sessionId,
                    x, y, x2, y2, durationMs)
            else -> RemoteAssistStreamWire.action(peer.relationId, session.sessionId, kind)
        }
        sendRemoteAssistPacket(peer, kind, session.sessionId, payload)
        return "操作已送达；如果老人没有手动启用辅助功能，手机将忽略本次操作"
    }

    private fun sendRemoteAssistPacket(peer: SavedPeer, kind: String,
                                       sessionId: String, payload: ByteArray) {
        // Only consent-scoped, signed control/ICE/SDP packets can use the
        // family's authenticated WSS relay. Captured frames are NEVER encoded
        // as RemoteAssistStreamWire messages; WebRTC media remains ICE/SRTP.
        require(RemoteAssistStreamWire.canUsePrivateRelay(kind)) {
            "远程协助中继仅允许已定义的信令与控制指令"
        }
        val message = signedRemoteStreamPacket(peer, kind, sessionId, payload)
        val node = verified[peer.relationId]?.let { nodes[it] }
        val result = sendSignedFrame(peer, node, LanTestWire.encode(message),
            4500, allowRelay = RemoteAssistStreamWire.canUsePrivateRelay(kind))
        if (result.viaRelay && kind == RemoteAssistStreamWire.RTC) {
            RemoteAssistDiagnostics.warning(
                "RTC 信令已通过家庭私人中继传送；视频仍需 ICE 建立加密连接")
        }
        val reply = LanTestWire.decode(result.bytes)
        require(reply.message.payloadKind == RemoteAssistStreamWire.ACK) {
            "远程协助数据没有签名回执"
        }
        val ack = RemoteAssistStreamWire.decode(reply.message)
        require(ack.relationId == peer.relationId && ack.sessionId == sessionId &&
            ack.respondsTo == message.message.messageId.value) { "远程协助回执不匹配" }
        require(admitRemoteStream(reply, peer, sessionId)) {
            "远程协助回执重复或过期"
        }
        result.directEndpoint?.let { direct.confirmSignedReply(peer.peerFingerprint, it) }
    }

    /** Isolated in-memory sequence + replay: streaming must not write SOS history per frame. */
    private fun signedRemoteStreamPacket(peer: SavedPeer, kind: String, sessionId: String,
                                         data: ByteArray): SignedEnvelope {
        val own = keys.createOrLoad().fingerprint
        val subject = if (peer.role == PairingRole.SUBJECT) own else peer.peerFingerprint
        val now = System.currentTimeMillis()
        val message = MessageEnvelope(1, MessageId(UUID.randomUUID().toString()),
            EventId(sessionId), DeviceId("okg-$own"), UserId("okg-user-$subject"),
            DeviceId("okg-${peer.peerFingerprint}"), now, now + 120_000,
            remoteStreamSequence.incrementAndGet(), kind, data)
        return SignedEnvelope(message, keys.signMessage(message))
    }

    private fun admitRemoteStream(signed: SignedEnvelope, peer: SavedPeer,
                                  sessionId: String): Boolean {
        val own = keys.createOrLoad().fingerprint
        val subject = if (peer.role == PairingRole.SUBJECT) own else peer.peerFingerprint
        require(signed.message.eventId.value == sessionId &&
            signed.message.subjectId.value == "okg-user-$subject") {
            "远程协助设备身份与会话不符"
        }
        val key = PairingQrProtocol.publicKey(Base64.getUrlDecoder()
            .decode(peer.peerPublicKeyBase64))
        val replayKey = sessionId + "|" + peer.peerFingerprint
        return synchronized(remoteStreamReplay) {
            when (val result = AuthenticatedAdmission.examine(signed,
                    DeviceId("okg-${peer.peerFingerprint}"), DeviceId("okg-$own"), key,
                    System.currentTimeMillis(),
                    remoteStreamReplay[replayKey] ?: ReplayWindow())) {
                is AdmissionResult.Accepted -> {
                    remoteStreamReplay[replayKey] = result.nextWindow
                    true
                }
                is AdmissionResult.Rejected -> {
                    if (result.reason == AdmissionFailure.DUPLICATE) false
                    else throw IllegalArgumentException("远程协助身份验证失败：${result.reason}")
                }
            }
        }
    }

    fun requestRemoteAssist(relationId: String): String {
        val peer = relations().singleOrNull {
            it.relationId == relationId && it.role == PairingRole.HELPER
        } ?: throw IllegalArgumentException("只能向已绑定的被帮助者申请远程协助")
        val session = remoteAssistConsent.request(relationId, peer.peerFingerprint)
        lastAssistExitNotice = null
        remotePeerDiagnosticSession = session.sessionId
        remotePeerDiagnostics = ""
        remotePeerDiagnosticAt = 0L
        RemoteAssistDiagnostics.begin("控制端")
        return try {
            sendRemoteAssistControl(peer, RemoteAssistWire.REQUEST, session.sessionId)
            signal()
            "远程协助请求已送达，等待对方在60秒内同意"
        } catch (failure: Exception) {
            remoteAssistConsent.clear()
            signal()
            throw failure
        }
    }

    /** Called only by the subject's explicit Agree/Reject UI action. */
    fun answerRemoteAssist(approve: Boolean): String {
        val session = remoteAssistConsent.snapshot()
            ?: throw IllegalStateException("当前没有待确认的远程协助请求")
        require(session.direction == RemoteAssistConsent.Direction.INCOMING &&
            session.phase == RemoteAssistConsent.Phase.WAITING_FOR_PERSON) {
            "当前没有待本人确认的请求"
        }
        val peer = relations().singleOrNull { it.relationId == session.relationId &&
            it.peerFingerprint == session.peerFingerprint &&
            it.role == PairingRole.SUBJECT }
            ?: throw IllegalArgumentException("家人关系已经撤销")
        if (approve) require(remoteAssistRequestsAllowed(session.relationId)) {
            "你已取消这位家人的远程协助权限，请先在主页恢复允许"
        }
        remoteAssistConsent.localDecision(session.sessionId, approve)
        if (approve) lastAssistExitNotice = null
        if (approve) RemoteAssistDiagnostics.begin("被控制端")
        return try {
            sendRemoteAssistControl(peer, if (approve) RemoteAssistWire.ACCEPT
                else RemoteAssistWire.DECLINE, session.sessionId)
            signal()
            if (approve) "已同意；请点击开始共享屏幕，再接受安卓的系统授权"
            else "已拒绝远程协助"
        } catch (failure: Exception) {
            remoteAssistConsent.clear() // A failed ACK must never retain a usable grant.
            signal()
            throw failure
        }
    }

    @Synchronized fun stopRemoteAssist(disconnectReason: String? = null): String {
        val session = remoteAssistConsent.snapshot() ?: return "当前没有远程协助会话"
        remoteAssistConsent.clear() // Local revoke must not depend on network availability.
        if (disconnectReason != null) {
            lastAssistExitNotice = AssistExitNotice(session.sessionId, disconnectReason)
            runCatching { S1TestNotifier.showRemoteAssistEnded(app, disconnectReason) }
        }
        remotePeerDiagnostics = ""
        remotePeerDiagnosticSession = ""
        RemoteAssistDiagnostics.finish()
        closeRemoteRtc()
        remoteStreamReplay.clear()
        if (session.direction == RemoteAssistConsent.Direction.INCOMING) {
            app.stopService(Intent(app, RemoteAssistProjectionService::class.java))
        }
        signal()
        val peer = relations().singleOrNull { it.relationId == session.relationId &&
            it.peerFingerprint == session.peerFingerprint }
        if (peer != null) runCatching {
            sendRemoteAssistControl(peer, RemoteAssistWire.STOP, session.sessionId)
        }
        return "已立即结束本机远程协助并清除屏幕画面"
    }

    /** Reuse only the authenticated existing LAN -> UDP P2P -> private relay path. */
    private fun sendRemoteAssistControl(peer: SavedPeer, kind: String,
                                        sessionId: String) {
        require(listener?.isClosed == false) { "请先启动应用的已认证连接" }
        val node = verified[peer.relationId]?.let { nodes[it] }
        require(node != null || direct.hasCandidates(peer.peerFingerprint) || relayConnected()) {
            "当前没有局域网、UDP直连候选或已连接的私人节点"
        }
        val payload = when (kind) {
            RemoteAssistWire.REQUEST -> RemoteAssistWire.request(peer.relationId, sessionId)
            RemoteAssistWire.ACCEPT -> RemoteAssistWire.accept(peer.relationId, sessionId)
            RemoteAssistWire.DECLINE -> RemoteAssistWire.decline(peer.relationId, sessionId)
            RemoteAssistWire.STOP -> RemoteAssistWire.stop(peer.relationId, sessionId)
            else -> throw IllegalArgumentException("不支持的远程协助指令")
        }
        val sent = signedMessage(peer, kind, sessionId, payload)
        val (bytes, usedRelay, directEndpoint) = sendSignedFrame(peer, node,
            LanTestWire.encode(sent), 8000)
        val reply = LanTestWire.decode(bytes)
        require(reply.message.payloadKind == RemoteAssistWire.RECEIPT) {
            "远程协助回执类型不匹配"
        }
        val body = RemoteAssistWire.body(reply.message)
        require(body.relationId == peer.relationId && body.sessionId == sessionId &&
            body.respondingTo == sent.message.messageId.value) {
            "远程协助签名回执不匹配"
        }
        verifyReply(reply, peer, kind, sessionId)
        if (usedRelay) relayVerifiedAt[peer.relationId] = System.currentTimeMillis()
        directEndpoint?.let { direct.confirmSignedReply(peer.peerFingerprint, it) }
    }
    fun selfTestPublicUdpMapping(): String = S5StunDiagnostic.run(app)
    fun verifiedByRelay(relationId: String): Boolean = relayConnected() &&
        (System.currentTimeMillis() - (relayVerifiedAt[relationId] ?: 0L)) in 0..120_000L

    fun setRelayOfflineQueueEnabled(enabled: Boolean) {
        store.update { it.copy(relayOfflineQueueEnabled = enabled) to Unit }
        signal()
    }

    fun setSimpleHome(enabled: Boolean) {
        store.update { it.copy(simpleHomeEnabled = enabled) to Unit }
        signal()
    }

    /** Reconfigure local warning times without resetting any genuine signed heartbeat. */
    fun setLossThresholds(warningMinutes: Int, criticalMinutes: Int) {
        require(warningMinutes in 3..30 && criticalMinutes in (warningMinutes + 1)..60) {
            "初级提醒需3～30分钟，严重提醒需更晚且不超过60分钟"
        }
        store.update { state ->
            state.copy(lossWarningMinutes = warningMinutes,
                lossCriticalMinutes = criticalMinutes,
                lastNotifiedLossStage = emptyMap()) to Unit
        }
        signal()
    }

    /** Battery thresholds are local diagnostics only; no automatic remote alerts. */
    fun setBatteryThresholds(warningPercent: Int, criticalPercent: Int) {
        require(warningPercent in 15..40 && criticalPercent in 5..14 &&
            criticalPercent < warningPercent) { "电量提醒阈值不符合设置范围" }
        store.update { it.copy(batteryWarningPercent = warningPercent,
            batteryCriticalPercent = criticalPercent) to Unit }
        signal()
    }

    /** Saving a node address is never equivalent to enabling a background service. */
    fun configureRelay(rawEndpoint: String, enabled: Boolean) {
        // Turning connectivity off must always work, even if the editable draft
        // currently contains an invalid URL; keep the last valid saved address.
        val endpoint = if (enabled) S2RelayPool.normalizeList(rawEndpoint).joinToString("\n")
            else store.snapshot().relayEndpoint
        store.update { state ->
            state.copy(relayEndpoint = endpoint, relayEnabled = enabled) to Unit
        }
        if (listener?.isClosed == false) relay.configure(endpoint, enabled)
        else relay.configure("", false)
        signal()
    }

    /** Renaming a relay never changes its pinned TLS identity or connection URL. */
    fun setRelayRemark(endpoint: String, remark: String) {
        val url = S2RelayClient.normalize(endpoint)
        require(remark.length <= 32 && remark.all { it.isLetterOrDigit() ||
            it.isWhitespace() || it in "-_（）()·." }) { "备注长度或字符不符合要求" }
        require(url in store.snapshot().relayEndpoint.lines()) { "请先保存该节点地址" }
        store.update { state ->
            state.copy(relayRemarks = state.relayRemarks.toMutableMap().apply {
                if (remark.isBlank()) remove(url) else put(url, remark.trim())
            }) to Unit
        }
        signal()
    }

    /** Public Nostr is distinct from the private protocol and opt-in per device. */
    fun configureNostrRelays(rawEndpoints: String, enabled: Boolean) {
        if (!enabled) direct.stop()
        val endpoints = if (enabled) S2NostrTransport.normalizeList(rawEndpoints)
            .joinToString("\n") else store.snapshot().nostrRelayEndpoints
        store.update { state ->
            state.copy(
                nostrRelayEndpoints = endpoints,
                nostrRelayEnabled = enabled,
                directUdpListenerEnabled = if (enabled) state.directUdpListenerEnabled else false
            ) to Unit
        }
        if (listener?.isClosed == false) publicNostr.configure(endpoints, enabled)
        else publicNostr.configure("", false)
        if (S5DirectListenerPolicy.shouldRestore(
                store.snapshot().directUdpListenerEnabled,
                enabled,
                localServiceRunning = listener?.isClosed == false
            )) direct.start()
        signal()
    }

    /** Relay packets must go through the exact existing signed LAN authorization path. */
    private fun forwardRelayInbound(packet: ByteArray): ByteArray {
        require(listener?.isClosed == false) { "本机监听已停止" }
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", LanTestWire.PORT), 1500)
            socket.soTimeout = 15_000
            writePacket(socket, packet)
            return readPacket(socket)
        }
    }

    /** The portal is a user-selected browser shortcut, never a remote device permission. */
    internal fun deviceFindPortal(fingerprint: String): OfficialDeviceFindPortal? {
        val url = store.snapshot().deviceFindPortals[fingerprint]
        return OfficialDeviceFindPortals.entries.firstOrNull { it.url == url }
    }

    fun setDeviceFindPortal(fingerprint: String, url: String?) {
        require(relations().any { it.peerFingerprint == fingerprint }) { "该设备未绑定" }
        require(url == null || OfficialDeviceFindPortals.entries.any { it.url == url }) {
            "只能选用内置的官方查找设备链接"
        }
        store.update { state ->
            state.copy(deviceFindPortals = if (url == null)
                state.deviceFindPortals - fingerprint else
                state.deviceFindPortals + (fingerprint to url)) to Unit
        }
        signal()
    }

    fun legacyDuplicateRelations(): List<List<SavedPeer>> = pairings.legacyDuplicateGroups()

    /** A known online node is identified by a signed heartbeat, never by its nickname. */
    fun hasVerifiedRoleWithNode(node: DiscoveredNode, role: PairingRole): Boolean =
        relations().any { it.role == role && verified[it.relationId] == node.serviceName }

    fun keepOneLegacyRelation(keepRelationId: String): Int {
        val retiredIds = pairings.keepOneLegacyRelation(keepRelationId)
        retiredIds.forEach { verified.remove(it) }
        store.update { state ->
            state.copy(locationSharingRelations = state.locationSharingRelations - retiredIds.toSet(),
                lastNotifiedLossStage = state.lastNotifiedLossStage - retiredIds.toSet()) to Unit
        }
        store.recordHandshake("本人整理", "DEDUPLICATE", keepRelationId, "旧记录停用",
            "保留用户选择的关系，已停用 ${retiredIds.size} 个同指纹同方向旧关系；" +
                "旧公钥未擦除，另一台设备不会被静默修改")
        signal()
        return retiredIds.size
    }

    /** Manual refresh is foreground-only; remote state is confirmed later by signed replies. */
    fun refreshNow(): List<String> {
        require(listener?.isClosed == false) { "局域网服务尚未启动；请打开应用后重试" }
        probeAsync()
        expireOldIncoming()
        return deliverPending()
    }

    fun ownNickname(): String = store.snapshot().let { state ->
        SystemNicknamePolicy.fromAndroid(app, state.ownNicknameCustomized, state.ownNickname)
    }

    fun usesSystemNickname(): Boolean = !store.snapshot().ownNicknameCustomized

    /** Foreground diagnostic: never saves or transmits the coordinates. */
    fun selfTestOwnGps(): String {
        val result = gps.selfCheck()
        if (!result.finePermission && !result.coarsePermission) {
            return "检测结果：应用尚未获得定位权限。请点击“申请定位权限”后重试。"
        }
        if (!result.gpsProviderEnabled && !result.networkProviderEnabled) {
            return "检测结果：系统 GPS 和网络定位均未开启。请在手机设置中打开定位开关。"
        }
        val fix = result.fix ?: return if (result.gpsProviderEnabled &&
            !result.finePermission) "检测结果：GPS 开关已开启，但应用仅有模糊定位权限；" +
            "请允许精确位置后重试。" else "检测结果：定位服务已开启，但10秒内未获得可靠位置。" +
            "请在室外或窗边重试；荣耀7X室内可能收不到卫星信号。"
        val method = if (fix.provider == "G") "GPS 卫星定位" else
            "网络定位（不代表已验证 GPS 卫星可用）"
        return "检测获得位置：$method；纬度 ${fix.latitude}，经度 ${fix.longitude}；" +
            "精度约 ${fix.accuracyMeters} 米；" +
            S3FixQuality.description(fix.provider, fix.accuracyMeters.toFloat()) + "；" +
            "采集时间 ${java.text.DateFormat.getDateTimeInstance().format(
                java.util.Date(fix.capturedAtMillis))}。结果仅显示在本机，不发送给其他设备。"
    }


    /** On-demand local readiness check. A connected socket is not proof of SOS delivery. */
    fun readinessReport(): String = S4ReadinessDiagnostics.evaluate(app,
        store.snapshot(), snapshot(), relations().size, S1Runtime.isBackgroundListening())

    fun setPreferredMapHandler(value: String) {
        require(ThirdPartyMapLauncher.availableDomestic(app).any { it.id == value }) {
            "所选地图软件未安装或不支持查看坐标，请重新选择"
        }
        store.update { it.copy(preferredMapHandler = value) to Unit }
        signal()
    }
    fun peerNickname(relationId: String): String =
        store.snapshot().peerNicknames[relationId].orEmpty()

    fun displayPeerNickname(relationId: String, fingerprint: String): String {
        val state = store.snapshot()
        return state.peerNicknames[relationId].orEmpty().ifBlank {
            state.verifiedPeerNicknames[relationId].orEmpty()
        }.ifBlank { state.verifiedDeviceNicknames[fingerprint].orEmpty() }
            .ifBlank { "设备 ${fingerprint.take(8)}" }
    }

    private fun checkedNickname(name: String): String {
        val normalized = name.trim()
        require(normalized.length in 1..18 && normalized.none {
            Character.isISOControl(it) || it == '\u202e' || it == '\u202d'
        }) { "昵称必须为1至18个正常字符，不可包含换行或控制符" }
        return normalized
    }

    fun renameOwnDevice(name: String) {
        val value = checkedNickname(name)
        store.update { it.copy(ownNickname = value, ownNicknameCustomized = true) to Unit }
        refreshAdvertisement()
        // An existing authenticated relationship can learn the new signed name even if
        // Android's mDNS cache retains an older untrusted TXT record.
        probeAsync()
        signal()
    }

    fun restoreSystemNickname() {
        store.update { it.copy(ownNickname = "", ownNicknameCustomized = false) to Unit }
        refreshAdvertisement()
        // An existing authenticated relationship can learn the new signed name even if
        // Android's mDNS cache retains an older untrusted TXT record.
        probeAsync()
        signal()
    }

    /** Test requests older than one day and never accepted are historical, not permanent alerts. */
    fun expireOldIncoming() {
        val now = System.currentTimeMillis()
        val outdated = store.snapshot().cases.filter { !it.ownSos &&
            it.supersededBy.isBlank() && it.status in setOf(ExerciseStatus.OPEN,
                ExerciseStatus.CLAIM_PENDING) && ExerciseMaintenance.staleUnclaimed(it.createdAt, now) }
        if (outdated.isEmpty()) return
        val expiredIds = outdated.map { it.eventId }.toSet()
        store.update { state ->
            state.copy(cases = state.cases.map { case ->
                if (case.eventId in expiredIds && !case.ownSos &&
                    case.status in setOf(ExerciseStatus.OPEN, ExerciseStatus.CLAIM_PENDING))
                    case.copy(status = ExerciseStatus.EXPIRED) else case
            }) to Unit
        }
        outdated.map { it.subjectFingerprint }.distinct().forEach { fingerprint ->
            S1TestNotifier.clearIncomingSos(app, fingerprint)
        }
        store.recordHandshake("维护", "EXPIRE", "", "已归档",
            "超过24小时无人接手的 ${outdated.size} 条演练请求已移入历史，不适用真实求救")
        signal()
    }

    fun renamePeer(relationId: String, name: String) {
        val value = checkedNickname(name)
        require(relations().any { it.relationId == relationId }) { "只允许给已绑定设备添加备注" }
        store.update { it.copy(peerNicknames = it.peerNicknames + (relationId to value)) to Unit }
        signal()
    }

    fun revokeRelation(relationId: String) {
        if (remoteAssistConsent.snapshot()?.relationId == relationId) stopRemoteAssist()
        val revoked = relations().firstOrNull { it.relationId == relationId }
        pairings.revoke(relationId) // Local-only. The other device is not silently modified.
        verified.remove(relationId)
        store.update { state ->
            state.copy(verifiedPeerNicknames = state.verifiedPeerNicknames - relationId,
                lastVerifiedAt = state.lastVerifiedAt - relationId,
                lastNotifiedLossStage = state.lastNotifiedLossStage - relationId,
                locationSharingRelations = state.locationSharingRelations - relationId,
                remoteAssistBlockedRelations = state.remoteAssistBlockedRelations - relationId,
                safetyZones = if (revoked != null &&
                    relations().none { it.peerFingerprint == revoked.peerFingerprint &&
                        it.role == PairingRole.HELPER })
                    state.safetyZones - revoked.peerFingerprint else state.safetyZones) to Unit
        }
        revoked?.let {
            S1TestNotifier.clearLanLost(app, it.peerFingerprint)
        }
        store.recordHandshake("关系变更", "REVOKE", relationId, "本机撤销",
            "停止认可此方向的公钥绑定；对方设备尚未同步撤销")
        signal()
    }

    /** Marking a received check-in read is the family's explicit confirmation action. */
    fun markIncomingCheckinsRead() {
        store.update { state ->
            val unread = state.checkins.filter {
                !it.own && it.timestamp > state.lastReadCheckinTimestamp
            }.map { it.eventId }.toSet()
            val latest = state.checkins.filterNot { it.own }.maxOfOrNull { it.timestamp } ?: 0L
            val now = System.currentTimeMillis()
            val updatedCheckins = state.checkins.map { item ->
                if (!item.own && item.eventId in unread) {
                    val progress = item.deliveryProgress[item.relationId] ?: DeliveryProgress()
                    item.copy(deliveryProgress = item.deliveryProgress +
                        (item.relationId to progress.copy(
                            familyConfirmedAt = maxOf(progress.familyConfirmedAt, now))))
                } else item
            }
            state.copy(lastReadCheckinTimestamp = maxOf(state.lastReadCheckinTimestamp, latest),
                checkins = updatedCheckins) to Unit
        }
        signal()
    }

    fun locationPermissionGranted(): Boolean = gps.permitted()

    fun setMedicalFolderTreeUri(value: String?) {
        if (!value.isNullOrBlank()) {
            val uri = Uri.parse(value)
            require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) {
                "只能使用系统文件选择器授权的本机文件夹"
            }
        }
        store.update { state -> state.copy(medicalFolderTreeUri = value.orEmpty()) to Unit }
        signal()
    }

    /** Keep concise operation outcomes in the existing AES-GCM encrypted local history. */
    fun recordCommandResult(action: String, outcome: String, code: String, detail: String) {
        require(outcome in setOf("完成", "待确认", "失败"))
        fun short(value: String, limit: Int) = value.replace(Regex("[\\r\\n\\t]+"), " ")
            .trim().take(limit)
        store.update { state ->
            state.copy(commandRecords = (state.commandRecords + CommandRecord(
                System.currentTimeMillis(), short(action, 32), outcome,
                short(code, 40), short(detail, 240))).takeLast(80)) to Unit
        }
        signal()
    }

    fun captureOwnLocation(): S3LocationWire.Fix? {
        require(S1Runtime.isUiVisible()) { "请在应用打开时手动获取位置" }
        require(gps.permitted()) { "请先允许系统定位权限" }
        return gps.capture()
    }

    /** Consent is local, per signed *subject -> helper* relation and OFF by default. */
    fun allowLocationToHelper(relationId: String, allowed: Boolean) {
        require(relations().any { it.relationId == relationId && it.role == PairingRole.SUBJECT }) {
            "仅可授权已绑定的救助者查看本机位置"
        }
        if (allowed) require(gps.permitted()) { "请先允许系统定位权限，再授权该救助者" }
        store.update { state ->
            state.copy(locationSharingRelations = if (allowed)
                state.locationSharingRelations + relationId else
                state.locationSharingRelations - relationId) to Unit
        }
        store.recordHandshake("定位授权", if (allowed) "LOCATION_ON" else "LOCATION_OFF",
            relationId, "本机确认", "仅前台单次签名定位请求；未授权后台追踪")
        signal()
    }

    /** This opt-out persists locally and blocks future signed REQUEST frames from this helper. */
    fun remoteAssistRequestsAllowed(relationId: String): Boolean =
        RemoteAssistPermissionPolicy.allowed(
            store.snapshot().remoteAssistBlockedRelations, relationId)

    @Synchronized fun setRemoteAssistRequestsAllowed(relationId: String, allowed: Boolean) {
        require(relations().any { it.relationId == relationId &&
            it.role == PairingRole.SUBJECT }) { "只能修改已绑定救助者的远程协助权限" }
        store.update { state ->
            state.copy(remoteAssistBlockedRelations = RemoteAssistPermissionPolicy.withPermission(
                state.remoteAssistBlockedRelations, relationId, allowed)) to Unit
        }
        if (!allowed) {
            val active = remoteAssistConsent.snapshot()
            if (active != null && active.relationId == relationId &&
                active.direction == RemoteAssistConsent.Direction.INCOMING) stopRemoteAssist()
        }
        store.recordHandshake("协助授权", if (allowed) "ASSIST_ON" else "ASSIST_OFF",
            relationId, "本机确认", if (allowed) "允许再次申请；每次仍需人工确认和系统共享授权" else
                "已禁止该帮助关系的远程协助申请并撤销本次授权")
        signal()
    }

    fun forgetReceivedLocations() {
        store.update { state ->
            state.copy(receivedLocations = emptyList(), safetyZoneEvents = emptyList(),
                safetyZones = state.safetyZones.mapValues { (_, zone) ->
                    zone.copy(position = SafetyZonePolicy.Position.UNKNOWN,
                        candidate = SafetyZonePolicy.Position.UNKNOWN,
                        candidateSinceMillis = 0)
                }) to Unit
        }
        signal()
    }

    /** Only the helper may configure a local, *foreground query* safety zone. */
    fun configureSafetyZone(relationId: String, radiusMeters: Int) {
        val peer = relations().singleOrNull {
            it.relationId == relationId && it.role == PairingRole.HELPER
        } ?: throw IllegalArgumentException("只能为已绑定的被救助者设置安全区域")
        val now = System.currentTimeMillis()
        store.update { state ->
            val fix = state.receivedLocations.lastOrNull {
                it.relationId == relationId && it.senderFingerprint == peer.peerFingerprint
            } ?: throw IllegalStateException("请先获得对方已授权的验签定位")
            require(!LocationMapPolicy.isHistorical(fix.capturedAtMillis, now)) {
                "最近定位已超过5分钟，请重新请求一次定位后再设置安全区域"
            }
            require(fix.accuracyCm <= radiusMeters * 50) {
                "当前定位误差较大，请选择更大半径或在室外重试"
            }
            val zone = SafetyZonePolicy.create(fix.latitudeE7, fix.longitudeE7,
                radiusMeters, fix.capturedAtMillis)
            state.copy(safetyZones = state.safetyZones + (peer.peerFingerprint to zone)) to Unit
        }
        store.recordHandshake("安全区域", "ZONE_SET", relationId, "本机配置",
            "仅在以后主动收到已授权且验签的定位时检查边界，不启用远端后台追踪")
        signal()
    }

    fun updateSafetyZoneRadius(relationId: String, radiusMeters: Int) {
        val peer = relations().firstOrNull {
            it.relationId == relationId && it.role == PairingRole.HELPER
        } ?: throw IllegalArgumentException("尚未绑定该被救助者")
        store.update { state ->
            val old = state.safetyZones[peer.peerFingerprint]
                ?: throw IllegalStateException("请先设置安全区域中心")
            state.copy(safetyZones = state.safetyZones +
                (peer.peerFingerprint to SafetyZonePolicy.withRadius(old, radiusMeters))) to Unit
        }
        signal()
    }

    fun removeSafetyZone(relationId: String) {
        val peer = relations().firstOrNull {
            it.relationId == relationId && it.role == PairingRole.HELPER
        } ?: throw IllegalArgumentException("尚未绑定该被救助者")
        store.update { state ->
            state.copy(safetyZones = state.safetyZones - peer.peerFingerprint) to Unit
        }
        signal()
    }

    /** Explicit plaintext export; caller must confirm disclosure in the system file picker. */
    fun exportReceivedLocationsGpx(): String {
        val locations = store.snapshot().receivedLocations
        require(locations.isNotEmpty()) { "还没有可导出的定位记录" }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"OpenKinGuard\" ")
            append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            locations.groupBy { it.relationId }.forEach { (_, fixes) ->
                append("<trk><name>已验证设备 ")
                append(fixes.first().senderFingerprint.take(8))
                append("</name><trkseg>\n")
                fixes.sortedBy { it.capturedAtMillis }.forEach { fix ->
                    append("<trkpt lat=\"")
                    append(String.format(java.util.Locale.US, "%.7f", fix.latitudeE7 / 1e7))
                    append("\" lon=\"")
                    append(String.format(java.util.Locale.US, "%.7f", fix.longitudeE7 / 1e7))
                    append("\"><time>")
                    append(java.time.Instant.ofEpochMilli(fix.capturedAtMillis).toString())
                    append("</time></trkpt>\n")
                }
                append("</trkseg></trk>\n")
            }
            append("</gpx>\n")
        }
    }

    /** Helper requests a fresh, explicitly authorized fix; never bypasses the subject's choice. */
    fun requestRemoteLocation(relationId: String): S3LocationWire.Fix {
        val peer = relations().singleOrNull {
            it.relationId == relationId && it.role == PairingRole.HELPER
        } ?: throw IllegalArgumentException("只能查看已绑定被救助者主动授权的位置")
        val endpoint = verified[relationId]?.let { nodes[it] }
        require((endpoint != null || relayConnected() || direct.hasCandidates(peer.peerFingerprint)) &&
            listener?.isClosed == false) {
            "对方尚无可用局域网、加密UDP候选或私人中继；请检查双方网络"
        }
        val eventId = UUID.randomUUID().toString()
        val ephemeral = S3LocationCrypto.ephemeral()
        val signed = signedMessage(peer, S3LocationWire.REQUEST, eventId,
            S3LocationWire.request(relationId, eventId,
                S3LocationCrypto.encodePublic(ephemeral.public)))
        val (replyBytes, usedRelay, directEndpoint) = sendSignedFrame(peer, endpoint,
            LanTestWire.encode(signed), 13_000)
        val reply = LanTestWire.decode(replyBytes)
        require(reply.message.payloadKind in setOf(S3LocationWire.RESULT, S3LocationWire.DENIED)) {
            "定位响应类型错误"
        }
        val body = S3LocationWire.body(reply.message)
        require(body.eventId == eventId && body.relationId == relationId &&
            body.respondingTo == signed.message.messageId.value) { "定位回执不匹配" }
        verifyReply(reply, peer, S3LocationWire.REQUEST, eventId)
        if (usedRelay) relayVerifiedAt[relationId] = System.currentTimeMillis()
        directEndpoint?.let { direct.confirmSignedReply(peer.peerFingerprint, it) }
        val encrypted = body.encrypted ?: throw RemoteLocationDenied(when (body.denial) {
            "NOT_ALLOWED" -> RemoteLocationDenial.NOT_ALLOWED
            "NO_PERMISSION" -> RemoteLocationDenial.NO_PERMISSION
            "FOREGROUND_ONLY" -> RemoteLocationDenial.FOREGROUND_ONLY
            else -> RemoteLocationDenial.UNAVAILABLE
        })
        val fix = S3LocationCrypto.decrypt(ephemeral.private, encrypted,
            relationId, eventId, signed.message.messageId.value)
        require(S3LocationWire.fresh(fix, System.currentTimeMillis())) {
            "定位结果已过期，未作为实时位置显示"
        }
        val zoneChange = store.update { state ->
            val now = System.currentTimeMillis()
            val inspection = state.safetyZones[peer.peerFingerprint]?.let { zone ->
                SafetyZonePolicy.observe(zone, fix.latitudeE7, fix.longitudeE7,
                    fix.accuracyCm, fix.capturedAtMillis)
            }
            val event = inspection?.transition?.let { transition ->
                SafetyZoneEvent(relationId, peer.peerFingerprint, transition,
                    fix.capturedAtMillis, inspection.zone.lastDistanceMeters)
            }
            state.copy(receivedLocations = (state.receivedLocations + ReceivedLocationFix(
                relationId, peer.peerFingerprint, fix.latitudeE7, fix.longitudeE7,
                fix.accuracyCm, fix.capturedAtMillis, now,
                fix.provider)).takeLast(200),
                safetyZones = if (inspection == null) state.safetyZones else
                    state.safetyZones + (peer.peerFingerprint to inspection.zone),
                safetyZoneEvents = if (event == null) state.safetyZoneEvents else
                    (state.safetyZoneEvents + event).takeLast(100)) to event
        }
        if (zoneChange != null) {
            val name = displayPeerNickname(relationId, peer.peerFingerprint)
            val action = if (zoneChange.transition == SafetyZonePolicy.Transition.LEFT)
                "已离开本机设定的安全区域" else "已返回本机设定的安全区域"
            S1TestNotifier.showSafetyZone(app, peer.peerFingerprint, "$name $action；" +
                "仅依据两次定位判断，请人工核实（非实时监控）")
        }
        store.recordHandshake("定位", "LOCATION_RESULT", relationId,
            "验签成功", "收到定位精度 ${fix.accuracyMeters} 米；原始坐标仅存入本机加密记录")
        signal()
        return fix
    }

    /** Explicit plaintext export: no keys, no automatic sharing, no contact phone numbers. */
    fun exportHistoryJson(): String {
        val state = store.snapshot()
        val root = JSONObject().put("format", "OpenKinGuard-history-v1")
            .put("exportedAt", System.currentTimeMillis())
            .put("deviceNickname", ownNickname())
            .put("retentionDays", state.retentionDays)
        root.put("cases", JSONArray().apply { state.cases.forEach { item ->
            put(JSONObject().put("eventId", item.eventId).put("relationId", item.relationId)
                .put("own", item.ownSos).put("createdAt", item.createdAt)
                .put("status", item.status.name)
                .put("subjectFingerprint", item.subjectFingerprint)
                .put("verifiedReceipts", item.deliveredRelations.size)
                .put("acceptedBy", item.acceptedBy)
                .put("supersededBy", item.supersededBy)
                .put("originatedAt", item.originatedAt))
        } })
        root.put("checkins", JSONArray().apply { state.checkins.forEach { item ->
            put(JSONObject().put("eventId", item.eventId).put("relationId", item.relationId)
                .put("own", item.own).put("createdAt", item.timestamp)
                .put("verifiedReceipts", item.deliveredRelations.size)
                .put("supersededBy", item.supersededBy)
                .put("originatedAt", item.originatedAt))
        } })
        root.put("locations", JSONArray().apply { state.receivedLocations.forEach { fix ->
            put(JSONObject().put("relationId", fix.relationId)
                .put("senderFingerprint", fix.senderFingerprint)
                .put("latitude", fix.latitudeE7 / 1e7)
                .put("longitude", fix.longitudeE7 / 1e7)
                .put("accuracyMeters", fix.accuracyCm / 100.0)
                .put("capturedAt", fix.capturedAtMillis)
                .put("receivedAt", fix.receivedAtMillis))
        } })
        root.put("safetyZoneEvents", JSONArray().apply { state.safetyZoneEvents.forEach { item ->
            put(JSONObject().put("relationId", item.relationId)
                .put("senderFingerprint", item.senderFingerprint)
                .put("transition", item.transition.name)
                .put("capturedAt", item.capturedAtMillis)
                .put("distanceMeters", item.distanceMeters))
        } })
        root.put("commands", JSONArray().apply { state.commandRecords.forEach { record ->
            put(JSONObject().put("time", record.time).put("action", record.action)
                .put("outcome", record.outcome).put("reasonCode", record.code)
                .put("detail", record.detail))
        } })
        return root.toString(2)
    }

    fun exportHistoryCsv(): String {
        val state = store.snapshot()
        fun field(value: String): String {
            val safe = if (value.firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
            return "\"${safe.replace("\"", "\"\"")}\""
        }
        val rows = mutableListOf(listOf("类别", "方向", "时间戳毫秒", "关系备注", "事件编号", "状态", "签名回执数"))
        state.cases.forEach { item ->
            rows += listOf("SOS演练", if (item.ownSos) "本机发起" else "本机收到",
                item.createdAt.toString(), state.peerNicknames[item.relationId].orEmpty(),
                item.eventId, if (item.supersededBy.isNotBlank()) "已被新请求替代"
                    else item.status.name, item.deliveredRelations.size.toString())
        }
        state.checkins.forEach { item ->
            rows += listOf("报平安", if (item.own) "本机发起" else "本机收到",
                item.timestamp.toString(), state.peerNicknames[item.relationId].orEmpty(),
                item.eventId, if (item.supersededBy.isNotBlank()) "已被新报平安替代"
                    else "已记录", item.deliveredRelations.size.toString())
        }
        state.safetyZoneEvents.forEach { item ->
            rows += listOf("安全区域演练", "本机判断", item.capturedAtMillis.toString(),
                state.peerNicknames[item.relationId].orEmpty(), "",
                if (item.transition == SafetyZonePolicy.Transition.LEFT) "离开（待人工核实）"
                else "返回", "")
        }
        return "\uFEFF" + rows.joinToString("\r\n") { row -> row.joinToString(",") { field(it) } } + "\r\n"
    }

    fun pendingPairing() = pairings.snapshot().session
    fun pairingSafetyCode(): String? = pairings.safetyCode()
    fun canContinueLanPairing() = lanPairingAddress != null
    fun pendingHelpOffer(): LanHelpOffer? = helpOffer?.takeIf {
        it.expiresAt > System.currentTimeMillis()
    }?.let { offer ->
        val latestAdvertisedName = nodes.values.firstOrNull { it.address == offer.address }
            ?.advertisedNickname.orEmpty()
        if (latestAdvertisedName.isNotBlank()) offer.copy(nickname = latestAdvertisedName)
        else offer
    }

    /** The existing signed four-frame protocol is carried over LAN; user approvals are condensed. */
    fun inviteOnLan(node: DiscoveredNode) {
        require(listener?.isClosed == false) { "请先打开局域网服务" }
        require(nodes[node.serviceName] == node) { "设备已离线，请重新发现" }
        require(!hasVerifiedRoleWithNode(node, PairingRole.SUBJECT)) {
            "这个设备指纹已是你的救助者，不能重复添加同一方向的帮助关系"
        }
        require(lanPairingAddress == null) { "已有一轮局域网配对进行中" }
        require(pendingHelpOffer() == null) { "请先结束上一条邀请" }
        val session: PairingSession
        synchronized(pairingGuard) {
            session = pairings.createInvitation().session ?: error("邀请创建失败")
            lanPairingAddress = node.address
            lanPairingToken = UUID.randomUUID().toString()
            val signedInvite = PairingQrProtocol.decodeAndVerify(session.invite,
                System.currentTimeMillis()).frame
            lanPairingSessionId = signedInvite.sessionId
            lanPairingRelationId = signedInvite.relationId
        }
        store.recordHandshake("发起", "INVITE", "待建立", "创建", "准备向附近设备发送签名邀请")
        sendLanFrame(session.invite)
        signal()
    }

    /** Helper-initiated request only. The other person must accept, then send a signed INVITE. */
    fun offerHelpOnLan(node: DiscoveredNode) {
        require(listener?.isClosed == false && nodes[node.serviceName] == node) { "设备已离线" }
        require(!hasVerifiedRoleWithNode(node, PairingRole.HELPER)) {
            "你已经是这台设备的救助者，不能重复添加同一方向的帮助关系"
        }
        val offer = LanHelpOffer(node.address, UUID.randomUUID().toString(),
            node.advertisedNickname.ifBlank { node.serviceName }, false,
            System.currentTimeMillis() + 120_000L)
        synchronized(pairingGuard) {
            require(pairings.snapshot().session == null && lanPairingAddress == null &&
                pendingHelpOffer() == null) { "已有绑定或邀请尚未完成" }
            helpOffer = offer
        }
        store.recordHandshake("发起", "OFFER_HELP", "待建立", "请求",
            "请求对端本人主动邀请我成为救助者；尚无信任授权")
        sendLanControl(node.address, JSONObject().put("action", "OFFER_HELP")
            .put("requestId", offer.requestId).put("nickname", ownNickname()))
        signal()
    }

    fun acceptHelpOffer() {
        val offer: LanHelpOffer
        val invite: String
        synchronized(pairingGuard) {
            offer = requireNotNull(pendingHelpOffer()?.takeIf { it.incoming }) { "邀请已失效" }
            require(pairings.snapshot().session == null && lanPairingAddress == null)
            invite = requireNotNull(pairings.createInvitation().session).invite
            lanPairingAddress = offer.address
            lanPairingToken = offer.requestId
            val signedInvite = PairingQrProtocol.decodeAndVerify(invite,
                System.currentTimeMillis()).frame
            lanPairingSessionId = signedInvite.sessionId
            lanPairingRelationId = signedInvite.relationId
            helpOffer = null
        }
        store.recordHandshake("本人同意", "OFFER_HELP", "待建立", "已接受",
            "对方希望成为我的救助者；我现发送独立签名邀请，之后核对一次安全码")
        sendLanFrame(invite)
        signal()
    }

    fun retryHelpOffer() {
        val offer = requireNotNull(pendingHelpOffer()?.takeIf { !it.incoming }) {
            "目前没有正在等待对方的邀请"
        }
        sendLanControl(offer.address, JSONObject().put("action", "OFFER_HELP")
            .put("requestId", offer.requestId).put("nickname", ownNickname()))
        signal()
    }

    fun confirmLanPairing() {
        require(lanPairingAddress != null) { "局域网会话已断开，请取消本轮后重新开始" }
        val phase = pairings.snapshot().session?.phase ?: error("没有待确认的绑定请求")
        require(phase in listOf(PairingPhase.REVIEW_INVITE, PairingPhase.REVIEW_RESPONSE,
            PairingPhase.REVIEW_GRANT)) { "当前尚未轮到本机确认" }
        val updated = pairings.confirm().session ?: error("没有配对会话")
        val outgoing = when (updated.phase) {
            PairingPhase.WAIT_GRANT -> updated.response
            PairingPhase.WAIT_ACK -> updated.grant
            PairingPhase.ACK_TO_SHOW -> updated.ack
            else -> error("配对步骤未预期")
        }
        store.recordHandshake("本机确认", phase.name, "待建立", "允许", "用户在本机明确确认绑定步骤")
        sendLanFrame(outgoing)
        if (updated.phase == PairingPhase.ACK_TO_SHOW) {
            // The subject returned an explicit transport receipt AFTER persisting the final
            // signed ACK. Both sides may now leave the safety-code screen automatically.
            pairings.finishHelperAfterAckShown()
            lanPairingAddress = null
            lanPairingToken = null
            lanPairingSessionId = null
            lanPairingRelationId = null
            store.recordHandshake("绑定", "COMPLETE", "已建立", "完成",
                "对方确认接收最终签名消息，双方可退出绑定界面")
        }
        probeAsync()
        signal()
    }

    fun finishLanPairing() {
        pairings.finishHelperAfterAckShown()
        lanPairingAddress = null
        lanPairingToken = null
        lanPairingSessionId = null
        lanPairingRelationId = null
        store.recordHandshake("本机确认", "COMPLETE", "已建立", "完成", "确认对方已完成最后一轮接收")
        probeAsync()
        signal()
    }

    /** Returns whether a still-pending remote screen confirmed the cancellation. */
    fun cancelLanPairing(): Boolean {
        val target: String?
        val packet: JSONObject?
        val hadUnfinishedWork: Boolean
        synchronized(pairingGuard) {
            val session = pairings.snapshot().session
            val offer = pendingHelpOffer()
            hadUnfinishedWork = (session != null && session.phase != PairingPhase.COMPLETE) ||
                offer != null
            target = if (session != null) lanPairingAddress else offer?.address
            packet = when {
                session != null && session.phase != PairingPhase.COMPLETE &&
                    lanPairingToken != null && lanPairingSessionId != null -> JSONObject()
                    .put("action", "CANCEL_PAIRING")
                    .put("token", lanPairingToken)
                    .put("sessionId", lanPairingSessionId)
                offer != null -> JSONObject()
                    .put("action", if (offer.incoming) "DECLINE_OFFER" else "CANCEL_OFFER")
                    .put("requestId", offer.requestId)
                else -> null
            }
            if (offer != null) closedOffers[offer.address + "|" + offer.requestId] =
                System.currentTimeMillis() + 120_000L
            pairings.cancelPending()
            lanPairingAddress = null
            lanPairingToken = null
            lanPairingSessionId = null
            lanPairingRelationId = null
            helpOffer = null
        }
        val confirmed = when {
            target != null && packet != null -> runCatching {
                sendLanControl(target, packet)
                true
            }.getOrElse { false }
            hadUnfinishedWork -> false
            else -> true
        }
        store.recordHandshake("本机", "CANCEL", "待建立",
            if (confirmed) "同步取消" else "未确认对端取消",
            "本机已结束等待；未撤销以前有效的帮助关系")
        signal()
        return confirmed
    }

    fun retryLanPairingStep() {
        val session = requireNotNull(pairings.snapshot().session) { "没有可重试的绑定" }
        val packet = when (session.phase) {
            PairingPhase.WAIT_RESPONSE -> session.invite
            PairingPhase.WAIT_GRANT -> session.response
            PairingPhase.WAIT_ACK -> session.grant
            PairingPhase.ACK_TO_SHOW -> session.ack
            else -> error("当前步骤已收到对方消息，无需重发")
        }
        require(packet.isNotBlank() && lanPairingAddress != null) { "局域网已断开" }
        sendLanFrame(packet)
        if (session.phase == PairingPhase.ACK_TO_SHOW) {
            pairings.finishHelperAfterAckShown()
            lanPairingAddress = null
            lanPairingToken = null
            lanPairingSessionId = null
            lanPairingRelationId = null
            probeAsync()
        }
        signal()
    }

    private fun sendLanFrame(encoded: String) {
        val address = requireNotNull(lanPairingAddress) { "远端设备连接已失效" }
        val token = requireNotNull(lanPairingToken) { "本轮绑定标识已失效" }
        val frame = PairingQrProtocol.decodeAndVerify(encoded, System.currentTimeMillis()).frame
        val body = JSONObject().put("action", "FRAME").put("frame", encoded).put("token", token)
        try {
            // Re-send the SAME signed bytes, not a newly authorized step. A lost TCP reply
            // can otherwise leave Android 10 waiting while Android 17 has progressed.
            sendLanControl(address, body, attempts = 3)
            store.recordHandshake("发出", frame.step.name, frame.relationId, "已接收",
                "签名帧经局域网送达；等待对方确认（不是完成授权）")
        } catch (error: Exception) {
            store.recordHandshake("发出", frame.step.name, frame.relationId, "失败",
                "传输回执未确认，可点击重发同一帧：${error.message ?: error.javaClass.simpleName}")
            throw IllegalStateException("对方可能已收到；请点击‘重发上一步’，无需重新授权", error)
        }
    }

    private fun sendLanControl(address: String, body: JSONObject, attempts: Int = 2) {
        val packet = (LAN_PAIR_PREFIX + body.toString()).toByteArray(Charsets.UTF_8)
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                val response = Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, LanTestWire.PORT), 1800)
                    socket.soTimeout = 2600
                    writePacket(socket, packet)
                    String(readPacket(socket), Charsets.UTF_8)
                }
                require(response == LAN_PAIR_ACCEPTED) { "对方没有接受本轮消息" }
                return
            } catch (error: Exception) {
                lastError = error
                if (attempt < attempts - 1) Thread.sleep(250L)
            }
        }
        throw IllegalStateException(lastError?.message ?: "对端响应超时", lastError)
    }

    private fun receiveLanFrame(socket: Socket, packet: ByteArray) {
        val remote = socket.inetAddress
        require(remote is Inet4Address && remote.isSiteLocalAddress) { "仅允许私有 IPv4 局域网绑定" }
        val ip = requireNotNull(remote.hostAddress) { "无法获取局域网来源地址" }
        val json = JSONObject(String(packet, Charsets.UTF_8).removePrefix(LAN_PAIR_PREFIX))
        var autoConfirmAfterReceipt = false
        when (json.optString("action", "FRAME")) {
            "OFFER_HELP" -> {
                val requestId = json.getString("requestId")
                require(UUID.fromString(requestId).toString() == requestId)
                val name = checkedNickname(json.getString("nickname"))
                synchronized(pairingGuard) {
                    require((closedOffers[ip + "|" + requestId] ?: 0L) < System.currentTimeMillis()) {
                        "此邀请已被拒绝或取消"
                    }
                    val old = pendingHelpOffer()
                    require(pairings.snapshot().session == null && lanPairingAddress == null &&
                        (old == null || old.address == ip && old.requestId == requestId && old.incoming)) {
                        "已有一轮配对正在进行"
                    }
                    helpOffer = LanHelpOffer(ip, requestId, name, true,
                        System.currentTimeMillis() + 120_000L)
                }
                store.recordHandshake("收到", "OFFER_HELP", "尚未建立", "等待本人同意",
                    "附近设备请求成为我的救助者，未经身份验证，绝不自动授权")
            }
            "CANCEL_OFFER", "DECLINE_OFFER" -> {
                val requestId = json.getString("requestId")
                synchronized(pairingGuard) {
                    val old = pendingHelpOffer()
                    if (old != null && old.address == ip && old.requestId == requestId &&
                        old.incoming == (json.getString("action") == "CANCEL_OFFER")) {
                        helpOffer = null
                        closedOffers[ip + "|" + requestId] = System.currentTimeMillis() + 120_000L
                        store.recordHandshake("对方", json.getString("action"), "尚未建立",
                            "取消", "对端退出待处理邀请")
                    } else if (json.getString("action") == "CANCEL_OFFER" &&
                        lanPairingAddress == ip && lanPairingToken == requestId &&
                        pairings.snapshot().session?.let {
                            it.role == PairingRole.SUBJECT && it.phase != PairingPhase.COMPLETE
                        } == true) {
                        // The subject accepted an OFFER and began a signed invitation while
                        // the helper was withdrawing the offer. The withdrawal still wins.
                        pairings.cancelPending()
                        lanPairingAddress = null
                        lanPairingToken = null
                        lanPairingSessionId = null
                        lanPairingRelationId = null
                        closedOffers[ip + "|" + requestId] = System.currentTimeMillis() + 120_000L
                        store.recordHandshake("对方", "CANCEL_OFFER", "待建立", "同步取消",
                            "对方撤回帮助请求，已退出并发的未完成绑定")
                    }
                }
            }
            "CANCEL_PAIRING" -> {
                val token = json.getString("token")
                val sessionId = json.getString("sessionId")
                require(UUID.fromString(token).toString() == token &&
                    UUID.fromString(sessionId).toString() == sessionId)
                synchronized(pairingGuard) {
                    val session = pairings.snapshot().session
                    val already = lastRemoteCancellation
                    if (session == null && already != null && already.first == ip &&
                        already.second == token && already.third > System.currentTimeMillis()) {
                        // A duplicate CANCEL after its reply was lost is harmless.
                    } else {
                        require(ip == lanPairingAddress && token == lanPairingToken &&
                            session != null) {
                            "取消请求与正在等待的绑定不符"
                        }
                        require(lanPairingSessionId == sessionId) { "取消请求的会话不匹配" }
                        if (session.phase == PairingPhase.COMPLETE) {
                            // ACK reached the subject but the last TCP receipt may have been
                            // lost. If helper abandons that still-open round, undo ONLY this
                            // newly created relationship, never an older unrelated one.
                            val relationId = requireNotNull(lanPairingRelationId)
                            if (pairings.snapshot().peers.any { it.active &&
                                    it.relationId == relationId }) pairings.revoke(relationId)
                        }
                        pairings.cancelPending()
                        lanPairingAddress = null
                        lanPairingToken = null
                        lanPairingSessionId = null
                        lanPairingRelationId = null
                        lastRemoteCancellation = Triple(ip, token,
                            System.currentTimeMillis() + 120_000L)
                        store.recordHandshake("对方", "CANCEL", "待建立", "同步取消",
                            "另一台设备退出绑定，本机已退出相同会话；旧关系未撤销")
                    }
                }
            }
            "FRAME" -> {
                val encoded = json.getString("frame")
                val token = json.getString("token")
                require(UUID.fromString(token).toString() == token)
                require((closedOffers[ip + "|" + token] ?: 0L) < System.currentTimeMillis()) {
                    "这一轮邀请已取消"
                }
                require(encoded.length in 100..4000) { "配对消息长度不合法" }
                val frame = PairingQrProtocol.decodeAndVerify(encoded, System.currentTimeMillis()).frame
                synchronized(pairingGuard) {
                    val existing = pairings.snapshot().session
                    if (existing == null) {
                        require(frame.step == PairingStep.INVITE && lanPairingAddress == null) {
                            "没有可接收的配对会话"
                        }
                        val outstanding = pendingHelpOffer()
                        require(outstanding == null || (!outstanding.incoming &&
                            outstanding.address == ip && outstanding.requestId == token)) {
                            "此设备有另一条邀请等待回应"
                        }
                    } else {
                        require(lanPairingAddress == ip && lanPairingToken == token) {
                            "配对来源或一次性标识不符"
                        }
                    }
                    // The same fully signed bytes can be safely re-delivered if TCP ACK was lost.
                    val before = existing?.phase
                    // For helper-initiated offers, pressing "request to help" already
                    // expressed this device's consent. Only the matching signed INVITE
                    // may advance automatically. An unsolicited INVITE still waits for
                    // explicit acceptance on the helper's screen.
                    val ownPendingOfferMatches = existing == null &&
                        pendingHelpOffer()?.let { !it.incoming && it.address == ip &&
                            it.requestId == token } == true
                    val after = pairings.scan(encoded)
                    if (existing == null) {
                        lanPairingAddress = ip
                        lanPairingToken = token
                        lanPairingSessionId = frame.sessionId
                        lanPairingRelationId = frame.relationId
                        helpOffer = null
                    }
                    if (before != after.session?.phase) {
                        store.recordHandshake("收到", frame.step.name, frame.relationId, "已验签",
                            "对端指纹 ${frame.senderFingerprint.take(20)}；等待必要的人工同意或安全码核对")
                        if (frame.step == PairingStep.ACK) store.recordHandshake("绑定",
                            "COMPLETE", frame.relationId, "完成", "四次签名交换已完成，关系已保存在本机")
                        autoConfirmAfterReceipt = LanSingleApprovalPolicy.autoConfirm(
                            after.session?.phase, ownPendingOfferMatches)
                    }
                }
            }
            else -> throw IllegalArgumentException("不支持的局域网绑定指令")
        }
        writePacket(socket, LAN_PAIR_ACCEPTED.toByteArray(Charsets.UTF_8))
        signal()
        if (autoConfirmAfterReceipt) {
            // Reply to this TCP frame first; then generate/send the next signed
            // frame. This avoids nested transport waits and preserves retries.
            Thread({
                runCatching { confirmLanPairing() }.onFailure { error ->
                    diagnostic("自动续接配对", error.message ?: error.javaClass.simpleName)
                }
            }, "okg-lan-pair-auto").apply { isDaemon = true; start() }
        }
    }

    /** Reports the actual bound socket and accept-loop thread, not a scheduler heartbeat. */
    @Synchronized internal fun tcpListenerHealth(): BackgroundTcpListenerState {
        val server = listener
        if (listenerUnexpectedlyClosed) return BackgroundTcpListenerState.CLOSED_UNEXPECTEDLY
        if (server == null) return if (listenerFailureCode == "TCP_BIND_FAILED")
            BackgroundTcpListenerState.FAILED else BackgroundTcpListenerState.NOT_STARTED
        if (server.isClosed) return BackgroundTcpListenerState.CLOSED_UNEXPECTEDLY
        if (!server.isBound) return BackgroundTcpListenerState.BINDING
        val thread = listenerAcceptThread ?: return BackgroundTcpListenerState.BINDING
        if (thread.state == Thread.State.NEW) return BackgroundTcpListenerState.BINDING
        if (thread.isAlive && !listenerLoopRunning) return BackgroundTcpListenerState.BINDING
        return if (thread.isAlive && listenerLoopRunning)
            BackgroundTcpListenerState.LISTENING
        else BackgroundTcpListenerState.CLOSED_UNEXPECTEDLY
    }

    @Synchronized fun start(onChange: () -> Unit) {
        changed = onChange
        val current = listener
        val currentThread = listenerAcceptThread
        if (current != null && !current.isClosed && current.isBound &&
            !listenerUnexpectedlyClosed && currentThread != null &&
            currentThread.state != Thread.State.TERMINATED) {
            val setting = store.snapshot()
            if (setting.nostrRelayEnabled) {
                runCatching { publicNostr.configure(setting.nostrRelayEndpoints, true) }
                    .onFailure { diagnostic("Nostr公共节点恢复", it.message ?: "配置无效") }
            }
            if (S5DirectListenerPolicy.shouldRestore(
                    setting.directUdpListenerEnabled,
                    setting.nostrRelayEnabled,
                    localServiceRunning = true
                )) runCatching { direct.start() }
                    .onFailure { diagnostic("持续UDP监听恢复", it.message ?: "无法恢复UDP监听") }
            return
        }

        // A dead accept loop must not leave an apparently bound but unusable socket behind.
        runCatching { current?.close() }
        listener = null
        listenerAcceptThread = null
        listenerLoopRunning = false
        listenerUnexpectedlyClosed = false
        listenerFailureCode = null
        inboundWorkers?.shutdownNow()
        inboundWorkers = null

        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(
                InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), LanTestWire.PORT))
        } catch (error: Exception) {
            listenerFailureCode = "TCP_BIND_FAILED"
            runCatching { server.close() }
            throw error
        }
        listener = server
        val workers = ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(32), { task ->
                Thread(task, "okg-s1-client").apply { isDaemon = true }
            }, ThreadPoolExecutor.AbortPolicy())
        inboundWorkers = workers
        val acceptThread = Thread({
            listenerLoopRunning = true
            try {
                while (!server.isClosed) {
                    try {
                        val socket = server.accept()
                        try {
                            workers.execute {
                                socket.use {
                                    try { processInbound(it); signal() }
                                    catch (error: Exception) {
                                        // Local diagnostics only; do not send untrusted error details.
                                        diagnostic("收到连接但未认证",
                                            error.message ?: error.javaClass.simpleName)
                                    }
                                }
                            }
                        } catch (_: java.util.concurrent.RejectedExecutionException) {
                            socket.close()
                            diagnostic("局域网连接拥堵", "过多连接，已拒绝新的未认证请求")
                        }
                    } catch (error: SocketException) {
                        if (!server.isClosed) {
                            listenerUnexpectedlyClosed = true
                            listenerFailureCode = "TCP_ACCEPT_SOCKET_EXCEPTION"
                            runCatching { server.close() }
                        }
                        break
                    } catch (_: Exception) {
                        if (server.isClosed) break
                        // A malformed connection cannot stop the listener.
                    }
                }
            } finally {
                listenerLoopRunning = false
                if (!server.isClosed && listener === server) {
                    listenerUnexpectedlyClosed = true
                    listenerFailureCode = listenerFailureCode ?: "TCP_ACCEPT_LOOP_EXITED"
                    runCatching { server.close() }
                }
            }
        }, "okg-s1-inbound").apply { isDaemon = true }
        listenerAcceptThread = acceptThread
        try {
            acceptThread.start()
        } catch (error: Exception) {
            listenerFailureCode = "TCP_ACCEPT_THREAD_START_FAILED"
            listenerUnexpectedlyClosed = true
            runCatching { server.close() }
            workers.shutdownNow()
            listener = null
            listenerAcceptThread = null
            inboundWorkers = null
            throw error
        }
        beginDiscovery()
        val setting = store.snapshot()
        remotePairing.resume()
        if (setting.relayEnabled) runCatching {
            relay.configure(setting.relayEndpoint, true)
        }.onFailure { diagnostic("公网中继配置", it.message ?: "配置无效") }
        if (setting.nostrRelayEnabled) runCatching {
            publicNostr.configure(setting.nostrRelayEndpoints, true)
        }.onFailure { diagnostic("Nostr公共节点配置", it.message ?: "配置无效") }
        if (S5DirectListenerPolicy.shouldRestore(
                setting.directUdpListenerEnabled,
                setting.nostrRelayEnabled,
                localServiceRunning = listener?.isClosed == false
            )) runCatching { direct.start() }
            .onFailure { diagnostic("持续UDP监听恢复", it.message ?: "无法恢复UDP监听") }
        signal()
    }

    @Synchronized fun stop() {
        remoteAssistConsent.clear()
        closeRemoteRtc()
        remoteStreamReplay.clear()
        app.stopService(Intent(app, RemoteAssistProjectionService::class.java))
        direct.stop()
        relay.configure("", false)
        publicNostr.configure("", false)
        relayVerifiedAt.clear()
        listener?.close()
        listener = null
        listenerAcceptThread = null
        listenerLoopRunning = false
        listenerUnexpectedlyClosed = false
        listenerFailureCode = null
        inboundWorkers?.shutdownNow()
        inboundWorkers = null
        advertiseRefreshPending = false
        browseListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        advertiseListener?.let { runCatching { nsd.unregisterService(it) } }
        browseListener = null
        advertiseListener = null
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
        multicastLock = null
        nodes.clear()
        verified.clear()
        resolveQueue.clear()
        discoveryError = ""
        signal()
    }

    private fun signal() {
        runCatching { changed?.invoke() }
        // The worker records only transitions or one minute snapshots; UI/network threads never write SQLite.
        ConnectionHistoryRecorder.schedule(app, this)
    }

    /** Re-register TXT metadata only after the previous advertisement is fully released. */
    @Synchronized private fun refreshAdvertisement() {
        if (listener?.isClosed != false || advertiseRefreshPending) return
        val previous = advertiseListener ?: return
        advertiseRefreshPending = true
        // Android NSD caches TXT records aggressively (especially Android 10).
        // A fresh service instance makes a newly chosen nickname discoverable without
        // treating any unauthenticated nickname as an identity or changing pinned keys.
        ownServiceName = "OKG-" + UUID.randomUUID().toString().take(12)
        runCatching { nsd.unregisterService(previous) }.onFailure {
            advertiseRefreshPending = false
            diagnostic("更新附近设备昵称", "系统未能重新广播；已签名昵称同步仍可使用")
        }
    }

    @Synchronized private fun registerAdvertisement() {
        val current = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { signal() }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                discoveryError = "服务广播失败，错误码 $error"
                signal()
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                synchronized(this@S1GuardManager) {
                    if (advertiseRefreshPending && advertiseListener === this &&
                        listener?.isClosed == false) {
                        advertiseRefreshPending = false
                        registerAdvertisement()
                    }
                }
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {
                advertiseRefreshPending = false
                diagnostic("更新附近设备昵称", "系统未能停止旧广播：$error")
            }
        }
        advertiseListener = current
        val info = NsdServiceInfo().apply {
            serviceName = ownServiceName
            serviceType = SERVICE
            port = LanTestWire.PORT
            setAttribute("protocol", "s1-test")
            setAttribute("nick", ownNickname())
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, current) }
            .onFailure { discoveryError = "服务广播异常：${it.javaClass.simpleName}"; signal() }
    }

    private fun beginDiscovery() {
        discoveryError = ""
        // Android 10 (and pre-T-ext 7) requires a Wi-Fi multicast lock for mDNS reception.
        runCatching {
            val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("openkinguard-s1-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        registerAdvertisement()
        browseListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { signal() }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName != ownServiceName && info.serviceType.contains("_openkinguard")) {
                    resolveQueue.add(info)
                    resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                nodes.remove(info.serviceName)
                verified.entries.removeIf { it.value == info.serviceName }
                signal()
            }
            override fun onDiscoveryStopped(type: String) { signal() }
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                discoveryError = "局域网发现失败，错误码 $error"
                signal()
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
        }
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD,
            requireNotNull(browseListener)) }.onFailure {
            discoveryError = "局域网发现异常：${it.javaClass.simpleName}"
            signal()
        }
    }

    /** Legacy NSD resolve calls must be serialized, especially on Android 10. */
    private fun resolveNext() {
        if (!resolving.compareAndSet(false, true)) return
        val pending = resolveQueue.poll()
        if (pending == null) { resolving.set(false); return }
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                diagnostic("设备发现解析", "系统返回错误码 $error")
                finish()
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val addresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) {
                    info.hostAddresses.ifEmpty { listOfNotNull(info.host) }
                } else listOfNotNull(info.host)
                val host = addresses.filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }?.hostAddress
                if (listener?.isClosed == false && host != null && info.port == LanTestWire.PORT &&
                    host !in localIpv4()) {
                    val nickname = info.attributes["nick"]?.let { bytes ->
                        String(bytes, Charsets.UTF_8).take(18)
                            .filterNot { Character.isISOControl(it) }
                    }.orEmpty()
                    nodes[info.serviceName] = DiscoveredNode(info.serviceName, host, info.port,
                        nickname)
                    signal()
                    probeAsync()
                } else if (host == null) {
                    diagnostic("设备发现解析", "未提供私有IPv4地址")
                }
                finish()
            }
            fun finish() { resolving.set(false); resolveNext() }
        }
        try { nsd.resolveService(pending, callback) }
        catch (error: Exception) {
            diagnostic("设备发现解析", error.message ?: error.javaClass.simpleName)
            resolving.set(false)
            resolveNext()
        }
    }

    private fun localIpv4(): Set<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    fun probeAsync(): CompletableFuture<Unit> = synchronized(probingLock) {
        // Reuse an in-flight probe even if the listener has just been closed;
        // callers must not release its bounded WakeLock before that work unwinds.
        probingCompletion?.takeIf { !it.isDone }?.let { return@synchronized it }
        if (listener?.isClosed != false)
            return@synchronized CompletableFuture.completedFuture(Unit)

        val completion = CompletableFuture<Unit>()
        probingCompletion = completion
        val worker = Thread({
            try {
                val existing = nodes.values.toList()
                for (peer in relations()) {
                    // The previously trusted service name may now point to a stale LAN endpoint.
                    // Verify every candidate with the pinned key and fall back on a fresh mDNS
                    // entry rather than indefinitely preserving a broken nickname route.
                    val pinned = verified[peer.relationId]
                    val candidates = existing.sortedBy { if (it.serviceName == pinned) 0 else 1 }
                    var authenticated = false
                    for (node in candidates) {
                        try {
                            request(peer, node, LanTestWire.PING, UUID.randomUUID().toString(), lanOnly = true)
                            verified[peer.relationId] = node.serviceName
                            if (pinned != node.serviceName)
                                diagnostic("公钥认证成功", "关系 ${peer.relationId.take(8)}，连接已更新")
                            signal()
                            runCatching { request(peer, node, S1CaseWire.PROFILE,
                                UUID.randomUUID().toString()) }.onFailure { error ->
                                diagnostic("同步设备昵称", error.message ?: error.javaClass.simpleName)
                            }
                            authenticated = true
                            break
                        } catch (error: Exception) {
                            if (pinned == node.serviceName) verified.remove(peer.relationId, pinned)
                        }
                    }
                    if (!authenticated && pinned != null) verified.remove(peer.relationId, pinned)
                    if (!authenticated && (relayConnected() ||
                            direct.hasCandidates(peer.peerFingerprint))) {
                        try {
                            request(peer, null, LanTestWire.PING, UUID.randomUUID().toString())
                            runCatching { request(peer, null, S1CaseWire.PROFILE,
                                UUID.randomUUID().toString()) }
                            signal()
                        } catch (error: Exception) {
                            if (!direct.verified(peer.peerFingerprint)) {
                                relayVerifiedAt.remove(peer.relationId)
                            }
                            if (direct.hasCandidates(peer.peerFingerprint)) {
                                diagnostic("S5直连验签检查",
                                    error.message ?: error.javaClass.simpleName)
                            }
                        }
                    }
                }
            } finally {
                synchronized(probingLock) {
                    if (probingCompletion === completion) probingCompletion = null
                }
                completion.complete(Unit)
            }
        }, "okg-s1-verify-discovery").apply { isDaemon = true }
        try {
            worker.start()
        } catch (error: Exception) {
            probingCompletion = null
            completion.completeExceptionally(error)
        }
        completion
    }

    /** Local LAN availability only. This is not proof of a person's safety or danger. */
    fun checkLiveness() {
        val peers = relations()
        val now = System.currentTimeMillis()
        for (peer in peers) {
            // Avoid an Android Keystore AES write every minute when nothing changed.
            val before = store.snapshot()
            val effectiveCritical = maxOf(before.lossWarningMinutes + 1,
                before.lossCriticalMinutes)
            if (PeerPresence.warningStage(before.lastVerifiedAt[peer.relationId], now,
                    before.lossWarningMinutes, effectiveCritical) <=
                (before.lastNotifiedLossStage[peer.relationId] ?: 0)) continue
            val newStage = store.update { state ->
                val warning = state.lossWarningMinutes
                val critical = state.lossCriticalMinutes.takeIf { it > warning } ?: 10
                val next = PeerPresence.warningStage(state.lastVerifiedAt[peer.relationId],
                    now, warning, maxOf(warning + 1, critical))
                val prior = state.lastNotifiedLossStage[peer.relationId] ?: 0
                if (next > prior) {
                    state.copy(lastNotifiedLossStage =
                        state.lastNotifiedLossStage + (peer.relationId to next)) to next
                } else state to 0
            }
            if (newStage > 0) {
                val nickname = displayPeerNickname(peer.relationId, peer.peerFingerprint)
                val config = store.snapshot()
                val failed = S1TestNotifier.showLanLost(app, peer.peerFingerprint,
                    nickname, newStage, if (newStage >= 2) config.lossCriticalMinutes
                        else config.lossWarningMinutes)
                if (failed.isNotBlank()) {
                    // Channel/permission might be enabled later. Do not mark an unseen
                    // notification as delivered permanently.
                    store.update { state ->
                        if (state.lastNotifiedLossStage[peer.relationId] == newStage)
                            state.copy(lastNotifiedLossStage =
                                state.lastNotifiedLossStage - peer.relationId) to Unit
                        else state to Unit
                    }
                } else if (store.snapshot().let { updated -> PeerPresence.warningStage(
                        updated.lastVerifiedAt[peer.relationId],
                        System.currentTimeMillis(), updated.lossWarningMinutes,
                        maxOf(updated.lossWarningMinutes + 1,
                            updated.lossCriticalMinutes)) } < newStage) {
                    // A signed reconnect may have raced with notification delivery.
                    S1TestNotifier.clearLanLost(app, peer.peerFingerprint)
                } else {
                    val state = store.snapshot()
                    store.recordHandshake("心跳", "LAN_OFFLINE", peer.relationId,
                        if (newStage == 2) "超过${state.lossCriticalMinutes}分钟"
                        else "超过${state.lossWarningMinutes}分钟",
                        "已绑定设备签名心跳中断；不代表人员遇险")
                    signal()
                }
            }
        }
    }

    fun createOwnSos(): String {
        val event = UUID.randomUUID().toString()
        val own = keys.createOrLoad().fingerprint
        store.update { state ->
            val created = maxOf(System.currentTimeMillis(),
                (state.cases.filter { it.ownSos }.maxOfOrNull { it.createdAt } ?: 0L) + 1L)
            val archived = state.cases.map { old ->
                if (old.ownSos && old.supersededBy.isBlank())
                    old.copy(supersededBy = event) else old
            }
            state.copy(cases = archived + ExerciseCase(event, "", own, true,
                created, originatedAt = created)) to Unit
        }
        S1TestNotifier.clearClaim(app, own)
        signal()
        return event
    }

    /** Voluntary non-emergency check-in; delivered only to explicitly authorized helpers. */
    fun createOwnCheckin(targetRelationId: String? = null): String {
        if (targetRelationId != null) require(relations().any {
            it.relationId == targetRelationId && it.role == PairingRole.SUBJECT
        }) { "只能给已绑定的救助者发送单独报平安" }
        val event = UUID.randomUUID().toString()
        val own = keys.createOrLoad().fingerprint
        store.update { state ->
            val created = maxOf(System.currentTimeMillis(),
                (state.checkins.filter { it.own }.maxOfOrNull { it.timestamp } ?: 0L) + 1L)
            val archived = state.checkins.map { old ->
                if (old.own && old.supersededBy.isBlank())
                    old.copy(supersededBy = event) else old
            }
            state.copy(checkins = (archived + CheckinRecord(event, "", own, true,
                created, originatedAt = created,
                targetRelationId = targetRelationId.orEmpty())).takeLast(100)) to Unit
        }
        signal()
        return event
    }

    fun retryCheckin(eventId: String) {
        store.update { state ->
            require(state.checkins.any { it.own && it.eventId == eventId &&
                it.supersededBy.isBlank() }) { "只能重试最新的报平安" }
            state.copy(checkins = state.checkins.map { if (it.eventId == eventId && it.own)
                it.copy(attempts = emptyMap()) else it }) to Unit
        }
        signal()
    }

    fun addContact(name: String, number: String) {
        val safePhone = number.trim().replace(" ", "").replace("-", "")
        require(safePhone.matches(Regex("\\+?[0-9]{3,18}"))) { "电话号码仅能包含数字及开头的 +" }
        require(name.isNotBlank() && name.length <= 40)
        store.update { state ->
            state.copy(contacts = (state.contacts + ManualContact(name.trim(), safePhone)).takeLast(10)) to Unit
        }
        signal()
    }

    fun removeContact(phone: String) {
        store.update { it.copy(contacts = it.contacts.filterNot { c -> c.phone == phone }) to Unit }
        signal()
    }

    fun setRetention(days: Int) {
        require(days in setOf(7, 30, 90))
        store.update { it.copy(retentionDays = days) to Unit }
        signal()
    }

    fun retryOwn(eventId: String) {
        store.update { state ->
            require(state.cases.any { it.ownSos && it.eventId == eventId &&
                it.supersededBy.isBlank() }) { "旧求助已归入历史，不能再发出" }
            state.copy(cases = state.cases.map {
                if (it.ownSos && it.eventId == eventId)
                    it.copy(attempts = emptyMap(), closeAttempts = emptyMap()) else it
            }) to Unit
        }
        signal()
    }

    private fun attempt(eventId: String, relationId: String, closing: Boolean): Boolean =
        store.update { state ->
            val item = state.cases.singleOrNull { it.ownSos && it.eventId == eventId }
                ?: return@update state to false
            val current = if (closing) item.closeAttempts else item.attempts
            val count = current[relationId] ?: 0
            val now = System.currentTimeMillis()
            if (closing) {
                if (!ExerciseMaintenance.shouldRetryClose(
                    item.closeLastAttemptAt[relationId] ?: 0L, count, now))
                    return@update state to false
            } else if (count >= MAX_FOREGROUND_ATTEMPTS) return@update state to false
            val next = item.copy(
                attempts = if (closing) item.attempts else item.attempts + (relationId to count + 1),
                closeAttempts = if (closing) item.closeAttempts +
                    (relationId to (count + 1).coerceAtMost(10_000)) else item.closeAttempts,
                closeLastAttemptAt = if (closing) item.closeLastAttemptAt + (relationId to now)
                    else item.closeLastAttemptAt)
            state.copy(cases = state.cases.map { if (it.ownSos && it.eventId == eventId)
                next else it }) to true
        }

    private fun attemptCheckin(eventId: String, relationId: String): Boolean = store.update { state ->
        val item = state.checkins.singleOrNull { it.own && it.eventId == eventId }
            ?: return@update state to false
        val count = item.attempts[relationId] ?: 0
        if (count >= MAX_FOREGROUND_ATTEMPTS) return@update state to false
        state.copy(checkins = state.checkins.map {
            if (it.own && it.eventId == eventId)
                it.copy(attempts = it.attempts + (relationId to count + 1)) else it
        }) to true
    }

    /** Creates and durably stores one immutable signed ciphertext frame before attempting transport. */
    private fun enqueueCaseOutbox(peer: SavedPeer, node: DiscoveredNode?,
                                  kind: String, eventId: String): DeliveryOutboxEntry {
        require(kind in setOf(S1CaseWire.SOS, S1CaseWire.CHECKIN,
            S1CaseWire.CLOSE, S1CaseWire.CHECKIN_CONFIRM))
        val now = System.currentTimeMillis()
        val existing = store.snapshot().deliveryOutbox.firstOrNull {
            it.eventId == eventId && it.relationId == peer.relationId &&
                it.peerFingerprint == peer.peerFingerprint && it.payloadKind == kind &&
                !S4DeliveryPolicy.isExpired(it.expiresAt, now)
        }
        if (existing != null) return existing

        ensurePeerEncryptionCard(peer, node)
        val state = store.snapshot()
        val sourceTime = when (kind) {
            S1CaseWire.SOS -> state.cases.firstOrNull { it.ownSos && it.eventId == eventId }?.createdAt
            S1CaseWire.CHECKIN -> state.checkins.firstOrNull { it.own && it.eventId == eventId }?.timestamp
            else -> null
        }
        val body = S1CaseWire.request(peer.relationId, eventId, sourceTime, ownNickname())
        val signed = signedMessage(peer, kind, eventId, body,
            validForMillis = S4DeliveryPolicy.MESSAGE_TTL_MILLIS)
        val frame = LanTestWire.encode(signed)
        val entry = DeliveryOutboxEntry(
            messageId = signed.message.messageId.value,
            eventId = eventId,
            relationId = peer.relationId,
            peerFingerprint = peer.peerFingerprint,
            payloadKind = kind,
            createdAt = signed.message.createdEpochMillis,
            expiresAt = signed.message.expiresEpochMillis,
            frameBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(frame),
        )
        return store.update { current ->
            val stillPending = current.deliveryOutbox.firstOrNull {
                it.eventId == eventId && it.relationId == peer.relationId &&
                    it.peerFingerprint == peer.peerFingerprint && it.payloadKind == kind &&
                    !S4DeliveryPolicy.isExpired(it.expiresAt, System.currentTimeMillis())
            }
            if (stillPending != null) current to stillPending
            else {
                val nowInside = System.currentTimeMillis()
                val retained = current.deliveryOutbox.filter {
                    !S4DeliveryPolicy.isExpired(it.expiresAt, nowInside) &&
                        !(it.eventId == eventId && it.relationId == peer.relationId &&
                            it.peerFingerprint == peer.peerFingerprint && it.payloadKind == kind)
                }
                require(retained.size < S4DeliveryPolicy.MAX_OUTBOX_ENTRIES) {
                    "本机可靠投递队列已满；请先恢复网络并完成待投递消息"
                }
                current.copy(deliveryOutbox =
                    (retained + entry).takeLast(S4DeliveryPolicy.MAX_OUTBOX_ENTRIES)) to entry
            }
        }
    }

    /** Persist retry state; relay acceptance is recorded separately from device receipt. */
    private fun markOutboxAttempt(messageId: String, relayAccepted: Boolean) {
        store.update { state ->
            val now = System.currentTimeMillis()
            val target = state.deliveryOutbox.firstOrNull { it.messageId == messageId }
                ?: return@update state to Unit
            val count = target.attemptCount + 1
            val acceptedAt = if (relayAccepted) now else target.relayAcceptedAt
            val updatedQueue = state.deliveryOutbox.map { entry ->
                if (entry.messageId == messageId) entry.copy(
                    attemptCount = count,
                    nextAttemptAt = now + S4DeliveryPolicy.retryDelayMillis(count),
                    relayAcceptedAt = acceptedAt,
                ) else entry
            }
            var updated = state.copy(deliveryOutbox = updatedQueue)
            if (relayAccepted) {
                val progress = DeliveryProgress(relayAcceptedAt = now)
                when (target.payloadKind) {
                    S1CaseWire.SOS, S1CaseWire.CLOSE -> updated = updated.copy(
                        cases = updated.cases.map { item ->
                            if (item.ownSos && item.eventId == target.eventId) {
                                val previous = item.deliveryProgress[target.relationId] ?: DeliveryProgress()
                                item.copy(deliveryProgress = item.deliveryProgress +
                                    (target.relationId to previous.copy(
                                        relayAcceptedAt = maxOf(previous.relayAcceptedAt, progress.relayAcceptedAt))))
                            } else item
                        })
                    S1CaseWire.CHECKIN -> updated = updated.copy(
                        checkins = updated.checkins.map { item ->
                            if (item.own && item.eventId == target.eventId) {
                                val previous = item.deliveryProgress[target.relationId] ?: DeliveryProgress()
                                item.copy(deliveryProgress = item.deliveryProgress +
                                    (target.relationId to previous.copy(
                                        relayAcceptedAt = maxOf(previous.relayAcceptedAt, progress.relayAcceptedAt))))
                            } else item
                        })
                    S1CaseWire.CHECKIN_CONFIRM -> updated = updated.copy(
                        checkins = updated.checkins.map { item ->
                            if (!item.own && item.eventId == target.eventId &&
                                item.relationId == target.relationId) {
                                val previous = item.deliveryProgress[target.relationId] ?: DeliveryProgress()
                                item.copy(deliveryProgress = item.deliveryProgress +
                                    (target.relationId to previous.copy(
                                        relayAcceptedAt = maxOf(previous.relayAcceptedAt, progress.relayAcceptedAt))))
                            } else item
                        })
                }
            }
            updated to Unit
        }
    }

    /**
     * The persisted ciphertext stays valid across process restarts. On a later send attempt
     * we re-encrypt it under a fresh signed sequence, preventing the 64-sequence replay
     * window from permanently rejecting a week-old SOS after many heartbeats.
     *
     * The message ID and absolute expiry never change, so device/event dedup remains stable.
     */
    private fun resealPendingCaseFrame(peer: SavedPeer, original: DeliveryOutboxEntry):
        DeliveryOutboxEntry {
        val now = System.currentTimeMillis()
        if (!S4DeliveryPolicy.shouldResealForRetry(original.attemptCount,
                original.expiresAt, now)) return original
        val state = store.snapshot()
        val sourceTime = when (original.payloadKind) {
            S1CaseWire.SOS -> state.cases.firstOrNull {
                it.ownSos && it.eventId == original.eventId
            }?.createdAt
            S1CaseWire.CHECKIN -> state.checkins.firstOrNull {
                it.own && it.eventId == original.eventId
            }?.timestamp
            else -> null
        }
        val body = S1CaseWire.request(original.relationId, original.eventId,
            sourceTime, ownNickname())
        // Never fall back to plaintext: if the cached signed card has expired while
        // offline, the previously sealed frame may still be retried unchanged.
        val signed = runCatching {
            signedMessage(peer, original.payloadKind, original.eventId, body,
                validForMillis = original.expiresAt - now)
        }.getOrNull() ?: return original
        require(signed.message.messageId.value == original.messageId) {
            "可靠投递重签后逻辑消息ID发生变化"
        }
        val frame = LanTestWire.encode(signed)
        require(frame.size <= S4DeliveryPolicy.MAX_FRAME_BYTES)
        val renewed = original.copy(
            createdAt = signed.message.createdEpochMillis,
            frameBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(frame))
        return store.update { current ->
            val found = current.deliveryOutbox.firstOrNull { it.messageId == original.messageId }
            if (found == null || found.expiresAt != original.expiresAt)
                current to original
            else current.copy(deliveryOutbox = current.deliveryOutbox.map {
                if (it.messageId == original.messageId) renewed else it
            }) to renewed
        }
    }

    /** Re-sign retries with the same stable message ID and original expiry. */
    private fun deliverCaseOutbox(peer: SavedPeer, node: DiscoveredNode?,
                                  kind: String, eventId: String): String? {
        var entry = enqueueCaseOutbox(peer, node, kind, eventId)
        val now = System.currentTimeMillis()
        if (S4DeliveryPolicy.isExpired(entry.expiresAt, now)) {
            store.update { state ->
                state.copy(deliveryOutbox = state.deliveryOutbox.filterNot {
                    it.messageId == entry.messageId
                }) to Unit
            }
            return null
        }
        if (!S4DeliveryPolicy.isDue(entry.nextAttemptAt, entry.expiresAt, now)) return null
        entry = resealPendingCaseFrame(peer, entry)
        val frame = Base64.getUrlDecoder().decode(entry.frameBase64)
        try {
            val (replyBytes, usedRelay, directEndpoint) = sendSignedFrame(peer, node, frame, 3000,
                allowTemporaryQueue = store.snapshot().relayOfflineQueueEnabled &&
                    kind in setOf(S1CaseWire.SOS, S1CaseWire.CHECKIN, S1CaseWire.CHECKIN_CONFIRM))
            val reply = LanTestWire.decode(replyBytes)
            require(reply.message.payloadKind in S1CaseWire.expectedResponse(kind)) {
                "签名回执类型与可靠投递消息不匹配"
            }
            verifyReply(reply, peer, kind, eventId, entry.messageId,
                if (usedRelay) System.currentTimeMillis() else entry.relayAcceptedAt)
            if (usedRelay) relayVerifiedAt[peer.relationId] = System.currentTimeMillis()
            directEndpoint?.let { direct.confirmSignedReply(peer.peerFingerprint, it) }
            return reply.message.payloadKind
        } catch (queued: RelayQueuedException) {
            markOutboxAttempt(entry.messageId, relayAccepted = true)
            throw queued
        } catch (error: Exception) {
            markOutboxAttempt(entry.messageId, relayAccepted = false)
            throw error
        }
    }

    /** Local encrypted outbox survives process death; optional relay RAM queue is not the source of truth. */
    /**
     * The mDNS cache and the verified service-name map can diverge after Wi-Fi
     * changes. Before declaring an SOS/check-in unroutable, challenge every
     * currently discovered LAN endpoint with the *pinned* peer identity.
     * A nickname/IP alone is never enough to authorize an endpoint.
     */
    private fun recoverVerifiedLanNode(peer: SavedPeer): DiscoveredNode? {
        val saved = verified[peer.relationId]
        val candidates = nodes.values.toList().sortedBy {
            if (it.serviceName == saved) 0 else 1
        }
        for (candidate in candidates) {
            try {
                request(peer, candidate, LanTestWire.PING, UUID.randomUUID().toString(), lanOnly = true)
                verified[peer.relationId] = candidate.serviceName
                return candidate
            } catch (failure: Exception) {
                diagnostic("局域网重验失败",
                    "关系 ${peer.relationId.take(8)} / ${candidate.serviceName.take(24)}：" +
                        (failure.message ?: failure.javaClass.simpleName))
            }
        }
        return null
    }

    private fun authenticatedLanNode(peer: SavedPeer): DiscoveredNode? =
        verified[peer.relationId]?.let { nodes[it] } ?: recoverVerifiedLanNode(peer)

    fun deliverPending(): List<String> {
        val errors = mutableListOf<String>()
        val state = store.snapshot()
        val currentOwn = state.cases.filter { it.ownSos && it.supersededBy.isBlank() &&
            it.status == ExerciseStatus.OPEN }.maxByOrNull { it.createdAt }
        val cutoff = System.currentTimeMillis() - state.retentionDays * 86_400_000L
        val closedToSync = state.cases.filter { it.ownSos &&
            it.status == ExerciseStatus.CLOSED && it.createdAt >= cutoff }.takeLast(20)
        val cases = closedToSync + listOfNotNull(currentOwn)
        val latestCheckin = state.checkins.filter { it.own && it.supersededBy.isBlank() }
            .maxByOrNull { it.timestamp }
        if ((cases.isNotEmpty() || latestCheckin?.deliveredRelations?.isEmpty() == true) &&
            relations().none { it.role == PairingRole.SUBJECT }) {
            return listOf("没有已授权帮助者：本机事件不会发给他人。双向求救需另一条反向帮助关系")
        }

        val pendingConfirmations = state.checkins.filter { item ->
            if (item.own) false else {
                val progress = item.deliveryProgress[item.relationId] ?: DeliveryProgress()
                progress.familyConfirmedAt > 0L && progress.deviceReceivedAt == 0L
            }
        }
        val activeEventIds = (cases.map { it.eventId } +
            listOfNotNull(latestCheckin?.eventId) + pendingConfirmations.map { it.eventId }).toSet()
        store.update { current ->
            current.copy(deliveryOutbox = current.deliveryOutbox.filter {
                it.eventId in activeEventIds &&
                    !S4DeliveryPolicy.isExpired(it.expiresAt, System.currentTimeMillis())
            }) to Unit
        }

        for (item in cases) {
            val closing = item.status == ExerciseStatus.CLOSED
            val kind = if (closing) S1CaseWire.CLOSE else S1CaseWire.SOS
            for (peer in relations().filter { it.role == PairingRole.SUBJECT }) {
                if (closing && peer.relationId in item.closedSyncedRelations) continue
                if (!closing && peer.relationId in item.deliveredRelations) continue
                val endpoint = authenticatedLanNode(peer)
                try {
                    deliverCaseOutbox(peer, endpoint, kind, item.eventId)
                } catch (error: Exception) {
                    val detail = if (error is RelayQueuedException)
                        "中继已接收暂存，但尚未收到手机签名回执"
                    else "SOS/结案尚未确认送达：" +
                        CommandFailureExplainer.explain(error).message
                    errors += detail
                }
            }
        }
        for (checkin in listOfNotNull(latestCheckin)) {
            for (peer in relations().filter { it.role == PairingRole.SUBJECT &&
                DeviceActionPolicy.canDeliverCheckin(checkin.targetRelationId, it.relationId) }) {
                if (peer.relationId in checkin.deliveredRelations) continue
                val endpoint = authenticatedLanNode(peer)
                try {
                    deliverCaseOutbox(peer, endpoint, S1CaseWire.CHECKIN, checkin.eventId)
                } catch (error: Exception) {
                    val detail = if (error is RelayQueuedException)
                        "中继已接收报平安暂存，但尚未收到手机签名回执"
                    else "报平安尚未收到签名回执：" +
                        CommandFailureExplainer.explain(error).message
                    errors += detail
                }
            }
        }
        for (checkin in pendingConfirmations) {
            val peer = relations().firstOrNull {
                it.role == PairingRole.HELPER && it.relationId == checkin.relationId
            } ?: continue
            val endpoint = authenticatedLanNode(peer)
            try {
                deliverCaseOutbox(peer, endpoint, S1CaseWire.CHECKIN_CONFIRM, checkin.eventId)
            } catch (error: Exception) {
                val detail = if (error is RelayQueuedException)
                    "中继已暂存家属确认，但尚未收到原设备签名回执"
                else "家属确认尚未回传：" + CommandFailureExplainer.explain(error).message
                errors += detail
            }
        }
        signal()
        return errors.distinct()
    }

    fun claim(eventId: String): String {
        val case = store.snapshot().cases.singleOrNull { !it.ownSos && it.eventId == eventId }
            ?: throw IllegalArgumentException("没有这条收到的演练报警")
        require(case.status == ExerciseStatus.OPEN && case.supersededBy.isBlank()) {
            "这条求助已经被接手、关闭或更新，请查看最新请求"
        }
        val peer = relations().singleOrNull { it.relationId == case.relationId &&
            it.role == PairingRole.HELPER } ?: throw IllegalArgumentException("对应授权已撤销")
        val endpoint = verified[peer.relationId]?.let { nodes[it] }
        require(endpoint != null || relayConnected() ||
            direct.hasCandidates(peer.peerFingerprint)) {
            "对方尚无局域网、UDP直连候选或私人中继"
        }
        return request(peer, endpoint, S1CaseWire.CLAIM, eventId)
    }

    fun closeOwn(eventId: String): List<String> {
        val fingerprint = store.update { state ->
            val target = state.cases.singleOrNull { it.eventId == eventId && it.ownSos }
                ?: throw IllegalArgumentException("未找到本机演练事件")
            require(target.status != ExerciseStatus.CLOSED && target.supersededBy.isBlank()) {
                "旧求助已经结束或被新求助取代"
            }
            state.copy(cases = state.cases.map { if (it.eventId == eventId && it.ownSos)
                it.copy(status = ExerciseStatus.CLOSED) else it }) to target.subjectFingerprint
        }
        S1TestNotifier.clearClaim(app, fingerprint)
        val errors = deliverPending()
        signal()
        return errors.distinct()
    }

    /** Sends one signed case packet; every SOS/check-in/control body is S4 encrypted. */
    private fun request(peer: SavedPeer, node: DiscoveredNode?, kind: String,
                        eventId: String, lanOnly: Boolean = false): String {
        require(node == null || node.port == LanTestWire.PORT)
        if (kind in S1CaseWire.encryptedKinds) ensurePeerEncryptionCard(peer, node)
        val body = when {
            kind == LanTestWire.PING -> LanTestWire.pingPayload(peer.relationId)
            kind == S1CaseWire.PROFILE -> S1CaseWire.request(peer.relationId, eventId,
                encryptionCard = publicNostr.ownMessageEncryptionCard())
            else -> {
                val createdAt = when (kind) {
                    S1CaseWire.SOS -> store.snapshot().cases.find { it.ownSos &&
                        it.eventId == eventId }?.createdAt
                    S1CaseWire.CHECKIN -> store.snapshot().checkins.find { it.own &&
                        it.eventId == eventId }?.timestamp
                    else -> null
                }
                S1CaseWire.request(peer.relationId, eventId, createdAt, ownNickname())
            }
        }
        require(listener?.isClosed == false) { "S1测试仅在应用前台联网" }
        val signed = signedMessage(peer, kind, eventId, body)
        val (replyBytes, usedRelay, directEndpoint) = sendSignedFrame(peer, node,
            LanTestWire.encode(signed), 3000,
            allowTemporaryQueue = store.snapshot().relayOfflineQueueEnabled &&
                kind in setOf(S1CaseWire.SOS, S1CaseWire.CHECKIN),
            lanOnly = lanOnly)
        val reply = LanTestWire.decode(replyBytes)
        val responseKind = reply.message.payloadKind
        if (kind == LanTestWire.PING) {
            require(responseKind == LanTestWire.ACK) { "不是签名测试回执" }
        } else {
            require(responseKind in S1CaseWire.expectedResponse(kind)) { "回执步骤不匹配" }
        }
        val beforeName = if (kind == S1CaseWire.PROFILE)
            store.snapshot().verifiedPeerNicknames[peer.relationId] else null
        verifyReply(reply, peer, kind, eventId, signed.message.messageId.value,
            if (usedRelay) System.currentTimeMillis() else 0L)
        if (usedRelay) relayVerifiedAt[peer.relationId] = System.currentTimeMillis()
        directEndpoint?.let { direct.confirmSignedReply(peer.peerFingerprint, it) }
        if (kind == S1CaseWire.PROFILE) {
            val afterName = store.snapshot().verifiedPeerNicknames[peer.relationId]
            if (afterName != null && afterName != beforeName) {
                store.recordHandshake("验证资料", "PROFILE", peer.relationId, "昵称更新",
                    "通过公钥签名及关联关系核对，更新对端显示名称：" + afterName.take(18))
            }
        } else {
            store.recordHandshake("发送并验回执", kind, peer.relationId, "成功",
                "对端公钥签名、消息编号、关联关系及有效期已核对；回执 " + responseKind)
        }
        signal()
        return responseKind
    }

    /** Refresh the signed card before use; no plaintext fallback is permitted. */
    private fun ensurePeerEncryptionCard(peer: SavedPeer, node: DiscoveredNode?) {
        val current = store.snapshot().peerEncryptionCards[peer.peerFingerprint]
        if (current != null && publicNostr.messageEncryptionPublic(current, peer) != null) return
        request(peer, node, S1CaseWire.PROFILE, UUID.randomUUID().toString())
        val refreshed = store.snapshot().peerEncryptionCards[peer.peerFingerprint]
        require(refreshed != null &&
            publicNostr.messageEncryptionPublic(refreshed, peer) != null) {
            "尚未取得对端有效的签名加密公钥；为防止安全降级，本次消息未发送"
        }
    }

    /** Route by public relationship UUID; expose command fields only after AES-GCM open. */
    private fun decryptCaseBody(signed: SignedEnvelope, peer: SavedPeer): S1CaseWire.Body {
        val message = signed.message
        if (message.payloadKind !in S1CaseWire.encryptedKinds) return S1CaseWire.body(message)
        val envelope = S4MessageWire.decode(message.payload)
        require(envelope.relationId == peer.relationId) { "加密报文关系不匹配" }
        val aad = S4MessageWire.associatedData(envelope.relationId, message)
        val plaintext = publicNostr.openCasePayload(envelope.sealed, aad)
        return S1CaseWire.bodyDecrypted(message, plaintext)
    }

    /** Correlates delayed signed device receipts with the persisted ciphertext outbox. */
    private fun handleLateRelayReply(frame: ByteArray) {
        val reply = runCatching { LanTestWire.decode(frame) }.getOrNull() ?: return
        val requestKind = when (reply.message.payloadKind) {
            S1CaseWire.RECEIPT -> S1CaseWire.SOS
            S1CaseWire.CHECKIN_RECEIPT -> S1CaseWire.CHECKIN
            S1CaseWire.CHECKIN_CONFIRM_RECEIPT -> S1CaseWire.CHECKIN_CONFIRM
            S1CaseWire.CLOSED -> S1CaseWire.CLOSE
            else -> return
        }
        val route = runCatching { S1CaseWire.body(reply.message) }.getOrNull() ?: return
        val peer = pairings.snapshot().peers.singleOrNull {
            it.active && it.relationId == route.relationId &&
                reply.message.senderDeviceId.value == "okg-" + it.peerFingerprint
        } ?: return
        val entry = store.snapshot().deliveryOutbox.firstOrNull {
            it.eventId == reply.message.eventId.value &&
                it.relationId == peer.relationId &&
                it.peerFingerprint == peer.peerFingerprint &&
                it.payloadKind == requestKind
        } ?: return
        runCatching {
            verifyReply(reply, peer, requestKind, entry.eventId, entry.messageId,
                entry.relayAcceptedAt)
        }.onSuccess {
            relayVerifiedAt[peer.relationId] = System.currentTimeMillis()
            store.recordHandshake("延迟送达回执", requestKind, peer.relationId, "成功",
                "已验签并关联原始离线消息 " + entry.messageId.take(12))
            signal()
        }.onFailure { error ->
            diagnostic("延迟签名回执", error.message ?: error.javaClass.simpleName)
        }
    }

    private data class SendResult(val bytes: ByteArray, val viaRelay: Boolean,
                                  val directEndpoint: InetSocketAddress?)

    /** LAN -> encrypted UDP direct -> separately opted-in private relay.
     * Free public Nostr relays NEVER handle these business frames.
     */
    private fun sendSignedFrame(peer: SavedPeer, node: DiscoveredNode?, frame: ByteArray,
                                timeoutMillis: Int,
                                allowTemporaryQueue: Boolean = false,
                                allowRelay: Boolean = true,
                                lanOnly: Boolean = false): SendResult {
        var lanFailure: String? = null
        var directFailure: String? = null
        if (node != null) {
            try {
                val answer = Socket().use { socket ->
                    socket.connect(InetSocketAddress(node.address, node.port), 2500)
                    socket.soTimeout = timeoutMillis
                    writePacket(socket, frame)
                    readPacket(socket)
                }
                return SendResult(answer, false, null)
            } catch (io: java.io.IOException) {
                lanFailure = io.message?.take(90)
            }
        }
        if (lanOnly) throw java.io.IOException("LAN签名验证未通过：" +
            (lanFailure ?: "局域网节点不存在"))
        // PING is the identity challenge used by recovery itself: never recurse
        // into recovery for a heartbeat, and never trust an mDNS nickname alone.
        val isIdentityProbe = runCatching {
            LanTestWire.decode(frame).message.payloadKind == LanTestWire.PING
        }.getOrDefault(false)
        if (!isIdentityProbe && (node == null || lanFailure != null)) {
            val recovered = recoverVerifiedLanNode(peer)
            if (recovered != null && recovered != node) {
                try {
                    val answer = Socket().use { socket ->
                        socket.connect(InetSocketAddress(recovered.address, recovered.port), 2500)
                        socket.soTimeout = timeoutMillis
                        writePacket(socket, frame)
                        readPacket(socket)
                    }
                    return SendResult(answer, false, null)
                } catch (failure: java.io.IOException) {
                    lanFailure = "重验后 TCP 失败：" +
                        (failure.message ?: failure.javaClass.simpleName).take(80)
                }
            }
        }
        if (direct.hasCandidates(peer.peerFingerprint)) {
            try {
                val result = direct.request(peer.peerFingerprint, frame, timeoutMillis)
                return SendResult(result.bytes, false, result.endpoint)
            } catch (io: java.io.IOException) {
                directFailure = io.message?.take(130)
            }
        }
        if (allowRelay && relay.connected) {
            try {
                return SendResult(relay.request(peer.peerFingerprint,
                    frame, allowTemporaryQueue), true, null)
            } catch (denied: RelayRouteException) {
                throw denied
            } catch (queued: RelayQueuedException) {
                throw queued // Only an ACK from the real peer can mean delivery.
            } catch (io: java.io.IOException) {
                throw io
            }
        }
        if (!allowRelay) throw java.io.IOException("远程屏幕和操作只允许局域网或 UDP P2P 直连；" +
            "当前没有可用直连，因此未发送画面。" +
            (directFailure?.let { "P2P：$it；" } ?: "") +
            (lanFailure?.let { "局域网：$it" } ?: ""))
        throw java.io.IOException("业务消息未得到对端签名回执。" +
            (lanFailure?.let { "局域网 TCP 失败：$it；" }
                ?: if (node == null) "当前没有已验签的局域网端点；" else "") +
            "UDP直连：" + (directFailure ?: "无可用的经过验证的候选地址") + "；" +
            if (relay.connected) "私人中继未完成业务发送"
            else "私人中继未连接；公共 Nostr 仅用于信令，不转发 SOS")
    }

    private fun processInbound(socket: Socket) {
        socket.soTimeout = 5000
        val packet = readPacket(socket)
        if (String(packet.take(LAN_PAIR_PREFIX.length).toByteArray(), Charsets.UTF_8) == LAN_PAIR_PREFIX) {
            receiveLanFrame(socket, packet)
            return
        }
        val signed = LanTestWire.decode(packet)
        val msg = signed.message
        require(msg.payloadKind == LanTestWire.PING || msg.payloadKind in S1CaseWire.requestKinds ||
            msg.payloadKind == S3LocationWire.REQUEST ||
            msg.payloadKind in RemoteAssistWire.requestKinds ||
            msg.payloadKind in RemoteAssistStreamWire.requestKinds) {
            "Unsolicited response"
        }
        val body = when (msg.payloadKind) {
            LanTestWire.PING -> LanTestWire.body(msg).relationId
            S3LocationWire.REQUEST -> S3LocationWire.body(msg).relationId
            in RemoteAssistWire.requestKinds -> RemoteAssistWire.body(msg).relationId
            in RemoteAssistStreamWire.requestKinds -> RemoteAssistStreamWire.decode(msg).relationId
            else -> S1CaseWire.body(msg).relationId
        }
        val peer = pairings.snapshot().peers.singleOrNull { it.active &&
            it.relationId == body && msg.senderDeviceId.value == "okg-${it.peerFingerprint}" }
            ?: throw IllegalArgumentException("Unpaired device")
        val own = keys.createOrLoad().fingerprint
        if (msg.payloadKind == S3LocationWire.REQUEST) {
            handleLocationRequest(socket, signed, peer, own)
            return
        }
        if (msg.payloadKind in RemoteAssistWire.requestKinds) {
            handleRemoteAssistControl(socket, signed, peer, own)
            return
        }
        if (msg.payloadKind in RemoteAssistStreamWire.requestKinds) {
            handleRemoteAssistStream(socket, signed, peer, own)
            return
        }
        var freshlyReceivedSos = false
        var freshlyReceivedCheckin = false
        var newlyAcceptedClaim = false
        var closedCurrentSos = false
        var newlyVerifiedHeartbeat = false
        val answerKind = store.update { state ->
            val admitted = admit(signed, peer, own, state)
            val duplicate = admitted == null
            // Decrypt only after the pinned device signature and replay window have passed.
            val inboundBody = if (!duplicate && msg.payloadKind != LanTestWire.PING)
                decryptCaseBody(signed, peer) else null
            if (!duplicate) newlyVerifiedHeartbeat = true
            var updated = if (duplicate) state else state.copy(replay = state.replay +
                (peer.relationId + "|" + peer.peerFingerprint to requireNotNull(admitted)),
                lastVerifiedAt = state.lastVerifiedAt +
                    (peer.relationId to System.currentTimeMillis()),
                lastNotifiedLossStage = state.lastNotifiedLossStage - peer.relationId)
            // A verified message carries the sender's chosen display name. This is not
            // an unauthenticated mDNS nickname and can immediately repair an empty cache.
            val signedNickname = inboundBody?.senderNickname
            val receivedCard = inboundBody?.encryptionCard
            if (!duplicate && receivedCard != null) {
                require(publicNostr.messageEncryptionPublic(receivedCard, peer) != null) {
                    "对端加密公钥卡签名或有效期无效；拒绝建立加密会话"
                }
                updated = updated.copy(peerEncryptionCards =
                    updated.peerEncryptionCards + (peer.peerFingerprint to receivedCard))
            }
            if (!duplicate && signedNickname != null) {
                updated = updated.copy(verifiedPeerNicknames =
                    updated.verifiedPeerNicknames + (peer.relationId to signedNickname),
                    verifiedDeviceNicknames =
                    updated.verifiedDeviceNicknames + (peer.peerFingerprint to signedNickname))
            }
            val replyKind = when (msg.payloadKind) {
                LanTestWire.PING -> LanTestWire.ACK
                S1CaseWire.SOS -> {
                    require(peer.role == PairingRole.HELPER) { "Only a subject can announce own SOS" }
                    val existing = state.cases.find { !it.ownSos && it.eventId == msg.eventId.value &&
                        it.subjectFingerprint == peer.peerFingerprint }
                    if (existing == null) {
                        require(!duplicate) { "Duplicate SOS was not stored" }
                        val originTime = inboundBody?.eventCreatedAtMillis ?: msg.createdEpochMillis
                        val latest = state.cases.filter { !it.ownSos &&
                            it.subjectFingerprint == peer.peerFingerprint && it.supersededBy.isBlank() }
                            .maxByOrNull { it.createdAt }
                        val newer = latest == null || CurrentEventWindow.arrivesAfter(
                            originTime, latest.originatedAt)
                        val archive = if (newer) updated.cases.map { prior ->
                            if (!prior.ownSos && prior.subjectFingerprint == peer.peerFingerprint &&
                                prior.supersededBy.isBlank()) prior.copy(supersededBy = msg.eventId.value)
                            else prior
                        } else updated.cases
                        updated = updated.copy(cases = archive + ExerciseCase(msg.eventId.value,
                            peer.relationId, peer.peerFingerprint, false, System.currentTimeMillis(),
                            supersededBy = if (newer) "" else requireNotNull(latest).eventId,
                            originatedAt = originTime))
                        freshlyReceivedSos = newer
                    }
                    S1CaseWire.RECEIPT
                }
                S1CaseWire.CHECKIN -> {
                    require(peer.role == PairingRole.HELPER) { "只有本人可以向已授权救助者报平安" }
                    val seen = state.checkins.any { !it.own && it.eventId == msg.eventId.value &&
                        it.fingerprint == peer.peerFingerprint }
                    if (!seen) {
                        require(!duplicate) { "未存储的重复报平安消息" }
                        val originTime = inboundBody?.eventCreatedAtMillis ?: msg.createdEpochMillis
                        val latest = state.checkins.filter { !it.own &&
                            it.fingerprint == peer.peerFingerprint && it.supersededBy.isBlank() }
                            .maxByOrNull { it.timestamp }
                        val newer = latest == null || CurrentEventWindow.arrivesAfter(
                            originTime, latest.originatedAt)
                        val archive = if (newer) updated.checkins.map { prior ->
                            if (!prior.own && prior.fingerprint == peer.peerFingerprint &&
                                prior.supersededBy.isBlank()) prior.copy(supersededBy = msg.eventId.value)
                            else prior
                        } else updated.checkins
                        updated = updated.copy(checkins = (archive + CheckinRecord(
                            msg.eventId.value, peer.relationId, peer.peerFingerprint,
                            false, System.currentTimeMillis(),
                            supersededBy = if (newer) "" else requireNotNull(latest).eventId,
                            originatedAt = originTime)).takeLast(100))
                        freshlyReceivedCheckin = newer
                    }
                    S1CaseWire.CHECKIN_RECEIPT
                }
                S1CaseWire.CHECKIN_CONFIRM -> {
                    require(peer.role == PairingRole.SUBJECT) {
                        "只有原报平安设备可以接收家属确认"
                    }
                    val ownCheckin = updated.checkins.firstOrNull {
                        it.own && it.eventId == msg.eventId.value &&
                            DeviceActionPolicy.canDeliverCheckin(it.targetRelationId, peer.relationId)
                    } ?: throw IllegalArgumentException("找不到这条关系下的原始报平安")
                    if (!duplicate) {
                        val now = System.currentTimeMillis()
                        updated = updated.copy(checkins = updated.checkins.map { item ->
                            if (item.own && item.eventId == ownCheckin.eventId) {
                                val progress = item.deliveryProgress[peer.relationId] ?: DeliveryProgress()
                                item.copy(deliveryProgress = item.deliveryProgress +
                                    (peer.relationId to progress.copy(
                                        familyConfirmedAt = maxOf(progress.familyConfirmedAt, now))))
                            } else item
                        })
                    }
                    S1CaseWire.CHECKIN_CONFIRM_RECEIPT
                }
                S1CaseWire.PROFILE -> S1CaseWire.PROFILE_REPLY
                S1CaseWire.CLAIM -> {
                    require(peer.role == PairingRole.SUBJECT) { "Only helper may request takeover" }
                    val ownCase = state.cases.find { it.ownSos && it.eventId == msg.eventId.value }
                    if (ownCase == null || ownCase.status == ExerciseStatus.CLOSED ||
                        ownCase.supersededBy.isNotBlank() ||
                        !S1CaseWire.claimDecision(ownCase.acceptedBy.ifEmpty { null },
                            peer.peerFingerprint)) S1CaseWire.REJECT
                    else {
                        newlyAcceptedClaim = ownCase.acceptedBy.isEmpty()
                        updated = updated.copy(cases = updated.cases.map {
                            if (it.eventId == ownCase.eventId && it.ownSos) {
                                val progress = it.deliveryProgress[peer.relationId] ?: DeliveryProgress()
                                it.copy(status = ExerciseStatus.ACCEPTED,
                                    acceptedBy = peer.peerFingerprint,
                                    deliveryProgress = it.deliveryProgress + (peer.relationId to
                                        progress.copy(familyConfirmedAt = maxOf(progress.familyConfirmedAt,
                                            System.currentTimeMillis()))))
                            } else it
                        })
                        S1CaseWire.ACCEPT
                    }
                }
                S1CaseWire.CLOSE -> {
                    require(peer.role == PairingRole.HELPER) { "Only subject may close own SOS" }
                    val known = state.cases.any { !it.ownSos &&
                        it.eventId == msg.eventId.value && it.relationId == peer.relationId }
                    closedCurrentSos = state.cases.any { !it.ownSos &&
                        it.eventId == msg.eventId.value && it.relationId == peer.relationId &&
                        it.supersededBy.isBlank() }
                    updated = if (known) updated.copy(cases = updated.cases.map {
                        if (!it.ownSos && it.eventId == msg.eventId.value &&
                            it.relationId == peer.relationId) it.copy(status = ExerciseStatus.CLOSED)
                        else it
                    }) else updated.copy(cases = updated.cases + ExerciseCase(
                        msg.eventId.value, peer.relationId, peer.peerFingerprint, false,
                        System.currentTimeMillis(), status = ExerciseStatus.CLOSED))
                    S1CaseWire.CLOSED
                }
                else -> error("Unexpected request kind")
            }
            updated to replyKind
        }
        if (freshlyReceivedSos) {
            // Notification OEM failures must never prevent a verified application-layer ACK.
            val notice = S1TestNotifier.showIncomingSos(app, peer.peerFingerprint,
                "${displayPeerNickname(peer.relationId, peer.peerFingerprint)} 发来测试 SOS，请打开应用查看")
            store.recordHandshake("收到", "SOS", peer.relationId,
                if (notice.isEmpty()) "通知已提交" else "通知未显示", notice.ifEmpty { "系统已接受通知请求" })
        }
        if (freshlyReceivedCheckin) {
            val notice = S1TestNotifier.showCheckin(app,
                "${displayPeerNickname(peer.relationId, peer.peerFingerprint)} 已报平安",
                peer.peerFingerprint)
            store.recordHandshake("收到", "CHECKIN", peer.relationId,
                if (notice.isEmpty()) "通知已提交" else "通知未显示",
                notice.ifEmpty { "收到真实签名回执协议中的非紧急消息" })
        }
        if (newlyAcceptedClaim) {
            val notice = S1TestNotifier.showClaim(app, own,
                "${displayPeerNickname(peer.relationId, peer.peerFingerprint)} 已接手你的测试 SOS")
            store.recordHandshake("收到", "CLAIM", peer.relationId,
                if (notice.isEmpty()) "已接手提醒已提交" else "已接手通知受限",
                notice.ifEmpty { "已核对帮助者签名，本机求助更新为已接手" })
        }
        if (closedCurrentSos) S1TestNotifier.clearIncomingSos(app, peer.peerFingerprint)
        val replyBody = when (answerKind) {
            LanTestWire.ACK -> LanTestWire.ackPayload(peer.relationId, msg.messageId.value)
            S1CaseWire.PROFILE_REPLY -> S1CaseWire.profileResponse(peer.relationId,
                msg.eventId.value, msg.messageId.value, ownNickname(),
                publicNostr.ownMessageEncryptionCard())
            else -> S1CaseWire.response(peer.relationId, msg.eventId.value, msg.messageId.value)
        }
        val reply = signedMessage(peer, answerKind, msg.eventId.value, replyBody)
        writePacket(socket, LanTestWire.encode(reply))
        if (newlyVerifiedHeartbeat) S1TestNotifier.clearLanLost(app, peer.peerFingerprint)
        if (msg.payloadKind != S1CaseWire.PROFILE) {
            store.recordHandshake("收到并答复", msg.payloadKind, peer.relationId, "成功",
                "验签已通过；签名答复 $answerKind；对端 ${peer.peerFingerprint.take(20)}")
        }
    }

    private fun handleLocationRequest(socket: Socket, signed: SignedEnvelope,
                                      peer: SavedPeer, own: String) {
        val msg = signed.message
        val body = S3LocationWire.body(msg)
        require(peer.role == PairingRole.SUBJECT) { "只有救助者可以请求被帮助者位置" }
        val consent = store.update { state ->
            val admitted = admit(signed, peer, own, state)
                ?: throw IllegalArgumentException("重复定位请求")
            state.copy(replay = state.replay +
                (peer.relationId + "|" + peer.peerFingerprint to admitted),
                lastVerifiedAt = state.lastVerifiedAt +
                    (peer.relationId to System.currentTimeMillis()),
                lastNotifiedLossStage = state.lastNotifiedLossStage - peer.relationId) to
                (peer.relationId in state.locationSharingRelations)
        }
        val permitted = gps.permitted()
        val fix = if (consent && permitted && S1Runtime.isUiVisible()) {
            runCatching { gps.capture() }.getOrNull()
        } else null
        // Foreground permission is not background permission. Recheck before disclosure.
        // The person may revoke this relation while a GPS fix is being acquired.
        // Never use only the pre-capture consent snapshot for disclosure.
        val stillConsented = peer.relationId in store.snapshot().locationSharingRelations
        val canDisclose = consent && stillConsented && permitted &&
            gps.permitted() && S1Runtime.isUiVisible() && fix != null &&
            S3LocationWire.fresh(fix, System.currentTimeMillis())
        val resultKind = if (canDisclose) S3LocationWire.RESULT else S3LocationWire.DENIED
        val resultBody = if (canDisclose) S3LocationWire.result(peer.relationId,
            body.eventId, msg.messageId.value, S3LocationCrypto.encrypt(
                requireNotNull(fix), requireNotNull(body.requestPublicKey),
                peer.relationId, body.eventId, msg.messageId.value))
        else S3LocationWire.denied(peer.relationId, body.eventId, msg.messageId.value, when {
            !consent || !stillConsented -> "NOT_ALLOWED"
            !permitted || !gps.permitted() -> "NO_PERMISSION"
            !S1Runtime.isUiVisible() -> "FOREGROUND_ONLY"
            else -> "UNAVAILABLE"
        })
        val reply = signedMessage(peer, resultKind, body.eventId, resultBody)
        writePacket(socket, LanTestWire.encode(reply))
        S1TestNotifier.clearLanLost(app, peer.peerFingerprint)
        store.recordHandshake("定位请求", resultKind, peer.relationId,
            if (canDisclose) "已授权并答复" else "安全拒绝",
            "请求经过公钥签名认证；坐标不会写入握手日志")
        signal()
    }

    /** Authenticated control sidecar; never mutates SOS, location or pairing permissions. */
    private fun handleRemoteAssistControl(socket: Socket, signed: SignedEnvelope,
                                          peer: SavedPeer, own: String) {
        val msg = signed.message
        val body = RemoteAssistWire.body(msg)
        when (msg.payloadKind) {
            RemoteAssistWire.REQUEST -> require(peer.role == PairingRole.SUBJECT) {
                "只有已绑定的监护人才能申请协助"
            }
            RemoteAssistWire.ACCEPT, RemoteAssistWire.DECLINE ->
                require(peer.role == PairingRole.HELPER) {
                    "只有被协助者可以同意或拒绝本次请求"
                }
        }
        val fresh = store.update { state ->
            val admitted = admit(signed, peer, own, state)
            if (admitted == null) state to false else
                state.copy(replay = state.replay +
                    (peer.relationId + "|" + peer.peerFingerprint to admitted),
                    lastVerifiedAt = state.lastVerifiedAt +
                    (peer.relationId to System.currentTimeMillis()),
                    lastNotifiedLossStage = state.lastNotifiedLossStage - peer.relationId) to true
        }
        var declineBlockedRequest = false
        if (fresh) when (msg.payloadKind) {
            RemoteAssistWire.REQUEST -> {
                if (!remoteAssistRequestsAllowed(peer.relationId)) {
                    declineBlockedRequest = true
                } else {
                    val previous = remoteAssistConsent.snapshot()
                    val accepted = remoteAssistConsent.receiveRequest(
                        peer.relationId, peer.peerFingerprint, body.sessionId)
                    if (accepted) lastAssistExitNotice = null
                    if (accepted && previous != null && previous.sessionId != body.sessionId) {
                        // A new session must never reuse an old projection grant.
                        closeRemoteRtc()
                        remoteStreamReplay.clear()
                        app.stopService(Intent(app, RemoteAssistProjectionService::class.java))
                    }
                }
            }
            // An unseen request may wait in memory for up to 60 seconds, but
            // never gains any control permission without a foreground click.
            RemoteAssistWire.ACCEPT -> remoteAssistConsent.remoteDecision(peer.relationId,
                peer.peerFingerprint, body.sessionId, true)
            RemoteAssistWire.DECLINE -> remoteAssistConsent.remoteDecision(peer.relationId,
                peer.peerFingerprint, body.sessionId, false)
            RemoteAssistWire.STOP -> if (remoteAssistConsent.stop(peer.relationId,
                    peer.peerFingerprint, body.sessionId)) {
                lastAssistExitNotice = AssistExitNotice(body.sessionId,
                    "对方已结束远程协助，屏幕共享和操作授权均已停止。")
                closeRemoteRtc()
                remoteStreamReplay.clear()
                app.stopService(Intent(app, RemoteAssistProjectionService::class.java))
            }
        }
        // The receipt only confirms signed delivery, never user consent.
        val reply = signedMessage(peer, RemoteAssistWire.RECEIPT, body.sessionId,
            RemoteAssistWire.receipt(peer.relationId, body.sessionId, msg.messageId.value))
        writePacket(socket, LanTestWire.encode(reply))
        signal()
        if (declineBlockedRequest) Thread({
            runCatching { sendRemoteAssistControl(peer, RemoteAssistWire.DECLINE, body.sessionId) }
        }, "okg-assist-blocked-decline").apply { isDaemon = true; start() }
    }

    /** Sidecar only: signed fragments/gestures are gated by this exact live session. */
    private fun handleRemoteAssistStream(socket: Socket, signed: SignedEnvelope,
                                         peer: SavedPeer, own: String) {
        val msg = signed.message
        val packet = RemoteAssistStreamWire.decode(msg)
        val fromSubject = msg.payloadKind == RemoteAssistStreamWire.DIAGNOSTIC
        val rtcSignal = msg.payloadKind == RemoteAssistStreamWire.RTC
        if (!rtcSignal) require(peer.role ==
            (if (fromSubject) PairingRole.HELPER else PairingRole.SUBJECT)) {
            "远程协助发送者的家人角色错误"
        }
        val session = remoteAssistConsent.snapshot()
        require(session != null && session.relationId == packet.relationId &&
            session.peerFingerprint == peer.peerFingerprint &&
            session.sessionId == packet.sessionId &&
            (session.direction != RemoteAssistConsent.Direction.INCOMING ||
                remoteAssistRequestsAllowed(session.relationId)) &&
            (if (fromSubject) session.direction == RemoteAssistConsent.Direction.OUTGOING &&
                session.phase in setOf(RemoteAssistConsent.Phase.AWAITING_SCREEN_PERMISSION,
                    RemoteAssistConsent.Phase.ACTIVE)
            else if (rtcSignal) {
                (session.direction == RemoteAssistConsent.Direction.OUTGOING &&
                    session.phase in setOf(RemoteAssistConsent.Phase.AWAITING_SCREEN_PERMISSION,
                        RemoteAssistConsent.Phase.ACTIVE)) ||
                (session.direction == RemoteAssistConsent.Direction.INCOMING &&
                    session.phase == RemoteAssistConsent.Phase.ACTIVE)
            } else session.direction == RemoteAssistConsent.Direction.INCOMING &&
                session.phase == RemoteAssistConsent.Phase.ACTIVE)) {
            "本次远程协助未经授权或已经结束"
        }
        if (msg.payloadKind in setOf(RemoteAssistStreamWire.TAP,
                RemoteAssistStreamWire.SWIPE, RemoteAssistStreamWire.BACK,
                RemoteAssistStreamWire.HOME, RemoteAssistStreamWire.RECENTS)) {
            require(RemoteAssistGestureService.enabled()) {
                "被控端无障碍服务未运行，不能执行远程控制；请在被控手机上手动启用"
            }
        }
        val fresh = admitRemoteStream(signed, peer, packet.sessionId)
        if (fresh) {
            when (msg.payloadKind) {
                RemoteAssistStreamWire.RTC -> {
                    val complete = rtcSignalAssembler.accept(packet)
                    if (complete != null) rtcFor(packet.sessionId)
                        .receive(complete.type, complete.text)
                }
                RemoteAssistStreamWire.DIAGNOSTIC -> {
                    remotePeerDiagnosticSession = packet.sessionId
                    remotePeerDiagnostics = packet.diagnosticText.take(1100)
                    remotePeerDiagnosticAt = System.currentTimeMillis()
                    signal()
                }
                RemoteAssistStreamWire.TAP -> require(RemoteAssistGestureService.tap(
                    packet.sessionId, packet.x, packet.y) {
                        authorizedRemoteControl(packet.sessionId)
                    }) { "被控端无障碍操作未被系统接受，请检查无障碍授权和锁屏状态" }
                RemoteAssistStreamWire.SWIPE -> require(RemoteAssistGestureService.swipe(
                    packet.sessionId, packet.x, packet.y, packet.x2, packet.y2,
                    packet.durationMs) { authorizedRemoteControl(packet.sessionId) }) {
                    "被控端无障碍操作未被系统接受，请检查无障碍授权和锁屏状态"
                }
                RemoteAssistStreamWire.BACK -> require(RemoteAssistGestureService.back(
                    packet.sessionId) { authorizedRemoteControl(packet.sessionId) }) {
                    "被控端无障碍操作未被系统接受，请检查无障碍授权和锁屏状态"
                }
                RemoteAssistStreamWire.HOME -> require(RemoteAssistGestureService.home(
                    packet.sessionId) { authorizedRemoteControl(packet.sessionId) }) {
                    "被控端无障碍操作未被系统接受，请检查无障碍授权和锁屏状态"
                }
                RemoteAssistStreamWire.RECENTS -> require(RemoteAssistGestureService.recents(
                    packet.sessionId) { authorizedRemoteControl(packet.sessionId) }) {
                    "被控端无障碍操作未被系统接受，请检查无障碍授权和锁屏状态"
                }
            }
        }
        val ack = signedRemoteStreamPacket(peer, RemoteAssistStreamWire.ACK, packet.sessionId,
            RemoteAssistStreamWire.ack(peer.relationId, packet.sessionId, msg.messageId.value))
        writePacket(socket, LanTestWire.encode(ack))
    }

    private fun verifyReply(reply: SignedEnvelope, peer: SavedPeer, requestKind: String,
                            eventId: String, expectedRequestMessageId: String? = null,
                            relayAcceptedAtMillis: Long = 0L) {
        val own = keys.createOrLoad().fingerprint
        store.update { state ->
            val admitted = admit(reply, peer, own, state)
                ?: throw IllegalArgumentException("重复的签名回执")
            val kind = reply.message.payloadKind
            val body = when {
                requestKind == LanTestWire.PING -> {
                    val expected = requireNotNull(expectedRequestMessageId)
                    val info = LanTestWire.body(reply.message)
                    require(kind == LanTestWire.ACK && info.relationId == peer.relationId &&
                        info.acknowledgedMessageId == expected) { "签名心跳回执不匹配" }
                    null
                }
                requestKind in S1CaseWire.requestKinds -> {
                    val expected = requireNotNull(expectedRequestMessageId)
                    require(kind in S1CaseWire.expectedResponse(requestKind)) { "回执步骤不匹配" }
                    val info = decryptCaseBody(reply, peer)
                    require(info.eventId == eventId && info.relationId == peer.relationId &&
                        info.respondingTo == expected) {
                        "签名回执与原始消息、关系或事件不匹配"
                    }
                    info
                }
                else -> null // Remote-assist and location callers validate their own typed bodies.
            }
            val now = System.currentTimeMillis()
            val pendingRelayAt = expectedRequestMessageId?.let { messageId ->
                state.deliveryOutbox.firstOrNull { it.messageId == messageId }?.relayAcceptedAt
            } ?: 0L
            val effectiveRelayAt = maxOf(relayAcceptedAtMillis, pendingRelayAt)
            fun received(progress: DeliveryProgress) = progress.copy(
                relayAcceptedAt = maxOf(progress.relayAcceptedAt, effectiveRelayAt),
                deviceReceivedAt = maxOf(progress.deviceReceivedAt, now))
            var updated = state.copy(replay = state.replay +
                (peer.relationId + "|" + peer.peerFingerprint to admitted),
                lastVerifiedAt = state.lastVerifiedAt + (peer.relationId to now),
                lastNotifiedLossStage = state.lastNotifiedLossStage - peer.relationId)
            updated = updated.copy(cases = updated.cases.map { item ->
                if (item.eventId != eventId) item else when (requestKind) {
                    S1CaseWire.SOS -> if (item.ownSos) {
                        val progress = item.deliveryProgress[peer.relationId] ?: DeliveryProgress()
                        item.copy(deliveredRelations = item.deliveredRelations + peer.relationId,
                            deliveryProgress = item.deliveryProgress +
                                (peer.relationId to received(progress)))
                    } else item
                    S1CaseWire.CLAIM -> if (!item.ownSos && item.relationId == peer.relationId) {
                        val progress = item.deliveryProgress[peer.relationId] ?: DeliveryProgress()
                        item.copy(status = if (kind == S1CaseWire.ACCEPT)
                            ExerciseStatus.ACCEPTED else ExerciseStatus.OPEN,
                            acceptedBy = if (kind == S1CaseWire.ACCEPT) own else item.acceptedBy,
                            deliveryProgress = item.deliveryProgress + (peer.relationId to
                                received(progress).copy(familyConfirmedAt = if (kind == S1CaseWire.ACCEPT)
                                    now else progress.familyConfirmedAt)))
                    } else item
                    S1CaseWire.CLOSE -> if (item.ownSos) {
                        val progress = item.deliveryProgress[peer.relationId] ?: DeliveryProgress()
                        item.copy(closedSyncedRelations = item.closedSyncedRelations + peer.relationId,
                            deliveryProgress = item.deliveryProgress +
                                (peer.relationId to received(progress)))
                    } else item
                    else -> item
                }
            })
            if (requestKind == S1CaseWire.CHECKIN) {
                updated = updated.copy(checkins = updated.checkins.map { item ->
                    if (item.own && item.eventId == eventId) {
                        val progress = item.deliveryProgress[peer.relationId] ?: DeliveryProgress()
                        item.copy(deliveredRelations = item.deliveredRelations + peer.relationId,
                            deliveryProgress = item.deliveryProgress +
                                (peer.relationId to received(progress)))
                    } else item
                })
            }
            if (requestKind == S1CaseWire.CHECKIN_CONFIRM) {
                updated = updated.copy(checkins = updated.checkins.map { item ->
                    if (!item.own && item.eventId == eventId && item.relationId == peer.relationId) {
                        val progress = item.deliveryProgress[peer.relationId] ?: DeliveryProgress()
                        item.copy(deliveryProgress = item.deliveryProgress +
                            (peer.relationId to received(progress)))
                    } else item
                })
            }
            if (requestKind == S1CaseWire.PROFILE) {
                val remoteName = requireNotNull(body?.profileNickname) { "对端未附有效昵称" }
                val card = body.encryptionCard
                if (card != null) {
                    require(publicNostr.messageEncryptionPublic(card, peer) != null) {
                        "对端加密公钥卡签名或有效期无效；拒绝安全降级"
                    }
                    updated = updated.copy(peerEncryptionCards =
                        updated.peerEncryptionCards + (peer.peerFingerprint to card))
                }
                updated = updated.copy(verifiedPeerNicknames =
                    updated.verifiedPeerNicknames + (peer.relationId to remoteName),
                    verifiedDeviceNicknames =
                    updated.verifiedDeviceNicknames + (peer.peerFingerprint to remoteName))
            }
            if (expectedRequestMessageId != null) {
                updated = updated.copy(deliveryOutbox =
                    updated.deliveryOutbox.filterNot { it.messageId == expectedRequestMessageId })
            }
            updated to Unit
        }
        S1TestNotifier.clearLanLost(app, peer.peerFingerprint)
    }

    private fun admit(signed: SignedEnvelope, peer: SavedPeer, own: String,
                      state: ExerciseState): ReplayWindow? {
        val msg = signed.message
        val subject = if (peer.role == PairingRole.SUBJECT) own else peer.peerFingerprint
        require(msg.subjectId.value == "okg-user-$subject") { "Wrong subject" }
        val key = PairingQrProtocol.publicKey(Base64.getUrlDecoder()
            .decode(peer.peerPublicKeyBase64))
        val replayKey = peer.relationId + "|" + peer.peerFingerprint
        return when (val outcome = AuthenticatedAdmission.examine(signed,
            DeviceId("okg-${peer.peerFingerprint}"), DeviceId("okg-$own"), key,
            System.currentTimeMillis(), state.replay[replayKey] ?: ReplayWindow())) {
            is AdmissionResult.Accepted -> outcome.nextWindow
            is AdmissionResult.Rejected -> {
                if (outcome.reason == AdmissionFailure.DUPLICATE &&
                    msg.payloadKind in S1CaseWire.requestKinds + LanTestWire.PING +
                        RemoteAssistWire.requestKinds) null
                else throw IllegalArgumentException("签名验证未通过：${outcome.reason}")
            }
        }
    }

    private fun signedMessage(peer: SavedPeer, kind: String, eventId: String,
                              body: ByteArray,
                              validForMillis: Long? = null): SignedEnvelope {
        val own = keys.createOrLoad().fingerprint
        val seqKey = peer.relationId + "|" + own
        val next = store.update { state ->
            val nextSeq = (state.sequences[seqKey] ?: -1L) + 1L
            require(nextSeq >= 0) { "Message sequence exhausted" }
            state.copy(sequences = state.sequences + (seqKey to nextSeq)) to nextSeq
        }
        val subject = if (peer.role == PairingRole.SUBJECT) own else peer.peerFingerprint
        val now = System.currentTimeMillis()
        // SOS/check-in packets may spend up to five minutes in one relay's
        // voluntary RAM queue, after up to one minute searching other nodes.
        // Other commands (especially GPS) retain the shorter two-minute TTL.
        val validFor = validForMillis ?: if (kind in setOf(S1CaseWire.SOS, S1CaseWire.CHECKIN)) 420_000L
            else 120_000L
        val senderId = DeviceId("okg-" + own)
        val recipientId = DeviceId("okg-" + peer.peerFingerprint)
        val messageId = if (S4DeliveryPolicy.usesStableMessageId(kind))
            S4MessageWire.stableMessageId(eventId, peer.relationId, senderId.value,
                recipientId.value, kind)
        else MessageId(UUID.randomUUID().toString())
        val header = MessageEnvelope(1, messageId, EventId(eventId), senderId,
            UserId("okg-user-" + subject), recipientId, now, now + validFor, next, kind,
            byteArrayOf(0))
        val payload = if (kind in S1CaseWire.encryptedKinds) {
            val card = store.snapshot().peerEncryptionCards[peer.peerFingerprint]
                ?: throw IllegalStateException("尚未取得对端签名加密公钥；拒绝发送明文")
            val recipientPublic = publicNostr.messageEncryptionPublic(card, peer)
                ?: throw IllegalStateException("对端签名加密公钥已过期或无效；请重新同步资料")
            val aad = S4MessageWire.associatedData(peer.relationId, header)
            S4MessageWire.encode(peer.relationId,
                publicNostr.sealCasePayload(body, recipientPublic, aad))
        } else body
        val message = header.copy(payload = payload)
        return SignedEnvelope(message, keys.signMessage(message))
    }

    private fun readPacket(socket: Socket): ByteArray = DataInputStream(socket.getInputStream()).run {
        val size = readInt()
        require(size in 10..LanTestWire.MAX_PACKET)
        ByteArray(size).also(::readFully)
    }

    private fun writePacket(socket: Socket, frame: ByteArray) {
        DataOutputStream(socket.getOutputStream()).apply {
            writeInt(frame.size)
            write(frame)
            flush()
        }
    }
}
