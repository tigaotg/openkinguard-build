package io.github.tigaotg.openkinguard

import android.content.Context
import io.github.tigaotg.openkinguard.security.protocol.PairingQrProtocol
import io.github.tigaotg.openkinguard.security.protocol.PairingStep
import org.json.JSONObject
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/**
 * Remote, previously unpaired devices meet through a short-lived random Nostr
 * mailbox. Relay operators can read routing metadata and public contact cards,
 * but not the encrypted four-frame handshake. This class cannot send SOS/GPS.
 */
internal class RemotePairingManager(
    context: Context,
    private val keys: AndroidDeviceKeyStore,
    private val pairings: LocalPairingManager,
    private val publicNostr: S2NostrTransport,
    private val onChanged: () -> Unit,
) {
    private val prefs = context.getSharedPreferences("okg-remote-pairing-v1",
        Context.MODE_PRIVATE)
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    data class Session(
        val link: String,
        val room: String,
        val role: PairingRole,
        val peerCard: String?,
        val expiresAt: Long,
        val peerFinished: Boolean = false,
    )

    @Synchronized
    fun current(): Session? = load()?.takeIf { state ->
        val signed = runCatching {
            PairingQrProtocol.decodeAndVerify(pairings.snapshot().session?.invite
                ?: return@takeIf false, minOf(System.currentTimeMillis(),
                    state.expiresAt - 1L)).frame
        }.getOrNull() ?: return@takeIf false
        state.room == RemotePairingInvitationCodec.room(signed.sessionId) &&
            state.expiresAt == signed.expiresAtEpochMillis
    }

    @Synchronized
    fun resume() {
        publicNostr.setRemoteRoom(current()?.takeIf {
            System.currentTimeMillis() < it.expiresAt
        }?.room)
    }

    /** The sender is explicitly choosing to be the help-receiving subject. */
    @Synchronized
    fun createInvite(relays: String): String {
        require(publicNostr.connected) { "请先在设置中启用公共节点，并等到信令订阅成功" }
        require(pairings.snapshot().session == null && load() == null) {
            "已有配对任务，请先完成或取消"
        }
        val endpoints = S2NostrTransport.normalizeList(relays)
        val invitation = pairings.createInvitation().session ?: error("无法生成邀请")
        val link = RemotePairingInvitationCodec.encode(invitation.invite,
            publicNostr.ownRemoteContactCard(), endpoints)
        val details = RemotePairingInvitationCodec.parse(link)
        save(Session(link, details.room, PairingRole.SUBJECT, null, details.expiresAt))
        publicNostr.setRemoteRoom(details.room)
        onChanged()
        return link
    }

    /** Import is local-only. A signed invite still requires the recipient's consent. */
    @Synchronized
    fun acceptInvite(raw: String, localRelays: String): String {
        require(publicNostr.connected) { "请先开启公共节点并等待连接成功" }
        require(pairings.snapshot().session == null && load() == null) {
            "已有配对任务，请先完成或取消"
        }
        val details = RemotePairingInvitationCodec.parse(raw)
        val own = keys.createOrLoad().fingerprint
        val signed = PairingQrProtocol.decodeAndVerify(details.frame,
            System.currentTimeMillis()).frame
        require(own != signed.senderFingerprint) { "不能邀请同一台设备" }
        require(publicNostr.verifyRemoteContact(details.contact, signed.senderFingerprint,
            encoder.encodeToString(signed.senderPublicKey),
            details.contact.getString("npub"))) { "邀请者公共节点名片与签名不一致" }
        val configured = S2NostrTransport.normalizeList(localRelays)
        require(configured.any { it in details.relays }) {
            "双方至少需要一个相同的公共节点；请先配置邀请中列出的公共节点"
        }
        pairings.scan(details.frame)
        save(Session(details.link, details.room, PairingRole.HELPER,
            details.contact.toString(), details.expiresAt))
        publicNostr.setRemoteRoom(details.room)
        onChanged()
        return signed.senderFingerprint.take(12)
    }

    /** Only a real user action can approve INVITE or the displayed 8-digit code. */
    @Synchronized
    fun approve(): String {
        val state = current() ?: error("没有有效的远程配对会话，或邀请已经过期")
        require(System.currentTimeMillis() < state.expiresAt) {
            "邀请已经过期，请取消后重新生成"
        }
        require(publicNostr.connected) { "公共节点未连接，请稍后重试" }
        val phase = pairings.snapshot().session?.phase
        require(phase in listOf(PairingPhase.REVIEW_INVITE, PairingPhase.REVIEW_RESPONSE,
            PairingPhase.REVIEW_GRANT)) { "当前尚未轮到本机确认" }
        // For the helper, REVIEW_GRANT follows the subject's earlier human
        // safety-code approval. No additional manual tap is needed.
        val session = pairings.confirm().session ?: error("签名状态不完整")
        val outgoing = when (session.phase) {
            PairingPhase.WAIT_GRANT -> session.response
            PairingPhase.WAIT_ACK -> session.grant
            PairingPhase.ACK_TO_SHOW -> session.ack
            else -> error("无法续接配对")
        }
        val sent = publishFrame(state, outgoing)
        onChanged()
        return if (sent) "已提交签名消息，等待另一台手机确认" else
            "签名已保存在本机，但节点暂未接受发送；请恢复连接后重发"
    }

    /** Recover from dropped packets without ever forging a new signed step. */
    @Synchronized
    fun retry(): String {
        val state = current() ?: error("没有有效的远程配对会话")
        require(System.currentTimeMillis() < state.expiresAt) {
            "邀请已经过期，请取消后重新生成"
        }
        val session = pairings.snapshot().session ?: error("配对会话不存在")
        if (session.phase == PairingPhase.COMPLETE && state.role == PairingRole.SUBJECT) {
            return if (publishReceipt(state, session.ack)) "已重新发送最终到达回执" else
                "公共节点暂不可用"
        }
        if (session.phase == PairingPhase.COMPLETE && state.role == PairingRole.HELPER) {
            return if (publishDone(state, session.ack)) "已重新发送双方完成确认" else
                "公共节点暂不可用"
        }
        val packet = when (session.phase) {
            PairingPhase.WAIT_GRANT -> session.response
            PairingPhase.WAIT_ACK -> session.grant
            PairingPhase.ACK_TO_SHOW -> session.ack
            else -> error("此阶段只能等待对方操作，或重新分享邀请")
        }
        return if (publishFrame(state, packet)) "已重新发送同一条签名消息" else
            "公共节点暂不可用"
    }

    @Synchronized
    fun cancel() {
        require(load() != null) { "没有远程配对任务" }
        pairings.cancelPending()
        prefs.edit().remove("session").commit()
        publicNostr.setRemoteRoom(null)
        onChanged()
    }

    @Synchronized
    fun finish() {
        val state = current() ?: error("没有远程绑定会话")
        require(pairings.snapshot().session?.phase == PairingPhase.COMPLETE) {
            "还未获得最终绑定回执"
        }
        require(state.role == PairingRole.HELPER || state.peerFinished) {
            "已保存本机关系，但尚未收到对方确实保存关系的签名确认"
        }
        pairings.cancelPending() // Keeps all completed peer relationships.
        prefs.edit().remove("session").commit()
        publicNostr.setRemoteRoom(null)
        onChanged()
    }

    /** Nostr signature has already been validated by the transport. */
    @Synchronized
    fun receive(event: JSONObject) {
        val state = current() ?: return
        val now = System.currentTimeMillis()
        if (now >= state.expiresAt) return
        val outer = JSONObject(event.getString("content"))
        if (outer.optString("type") != "remote-pairing" ||
            outer.optString("room") != state.room ||
            outer.optLong("exp") != state.expiresAt) return
        val step = outer.getString("step")
        if (step !in setOf("RESPONSE", "GRANT", "ACK", "RECEIPT", "DONE")) return
        val aad = RemotePairingInvitationCodec.aad(state.room, step, state.expiresAt)
        val plaintext = publicNostr.openRemotePairing(outer.getJSONObject("box"), aad)
        val envelope = JSONObject(String(plaintext, Charsets.UTF_8))
        if (envelope.optInt("v") != 1 ||
            envelope.optString("sender") == keys.createOrLoad().fingerprint ||
            envelope.optString("recipient") != keys.createOrLoad().fingerprint) return
        val body = envelope.getJSONObject("payload")
        val signed = pairings.snapshot().session ?: return
        val peerCard = envelope.getJSONObject("card")
        val eventPublic = event.getString("pubkey")
        val expectedPriorPeer = state.peerCard?.let { JSONObject(it) }
        if (expectedPriorPeer != null &&
            (expectedPriorPeer.optString("npub") != peerCard.optString("npub") ||
             expectedPriorPeer.optString("epub") != peerCard.optString("epub") ||
             expectedPriorPeer.optString("fp") != peerCard.optString("fp"))) return

        if (step == "RECEIPT") {
            if (state.role != PairingRole.HELPER ||
                signed.phase !in setOf(PairingPhase.ACK_TO_SHOW, PairingPhase.COMPLETE)) return
            val inviteFrame = PairingQrProtocol.decodeAndVerify(signed.invite, now).frame
            if (inviteFrame.senderFingerprint != envelope.optString("sender") ||
                !publicNostr.verifyRemoteContact(peerCard, inviteFrame.senderFingerprint,
                    encoder.encodeToString(inviteFrame.senderPublicKey), eventPublic)) return
            val digest = PairingQrProtocol.fingerprint(signed.ack.toByteArray(Charsets.UTF_8))
            if (body.optString("ack") != digest || body.optInt("v") != 1) return
            val signature = decoder.decode(body.getString("sig"))
            if (!Signature.getInstance("SHA256withECDSA").run {
                    initVerify(PairingQrProtocol.publicKey(inviteFrame.senderPublicKey))
                    update("OpenKinGuard|remote-pairing-ack-receipt|v1|"
                        .toByteArray(Charsets.US_ASCII))
                    update(digest.toByteArray(Charsets.US_ASCII))
                    verify(signature)
                }) return
            if (signed.phase == PairingPhase.ACK_TO_SHOW) pairings.finishHelperAfterAckShown()
            publishDone(state, signed.ack)
            onChanged()
            return
        }
        if (step == "DONE") {
            if (state.role != PairingRole.SUBJECT ||
                signed.phase != PairingPhase.COMPLETE) return
            val response = PairingQrProtocol.decodeAndVerify(signed.response, now).frame
            if (response.senderFingerprint != envelope.optString("sender") ||
                !publicNostr.verifyRemoteContact(peerCard, response.senderFingerprint,
                    encoder.encodeToString(response.senderPublicKey), eventPublic)) return
            val digest = PairingQrProtocol.fingerprint(signed.ack.toByteArray(Charsets.UTF_8))
            if (body.optInt("v") != 1 || body.optString("ack") != digest) return
            val proof = decoder.decode(body.getString("sig"))
            if (!Signature.getInstance("SHA256withECDSA").run {
                    initVerify(PairingQrProtocol.publicKey(response.senderPublicKey))
                    update("OpenKinGuard|remote-pairing-saved|v1|"
                        .toByteArray(Charsets.US_ASCII))
                    update(digest.toByteArray(Charsets.US_ASCII))
                    verify(proof)
                }) return
            save(state.copy(peerFinished = true))
            onChanged()
            return
        }

        val encoded = body.getString("frame")
        val frame = PairingQrProtocol.decodeAndVerify(encoded, now).frame
        if (frame.step.name != step || frame.senderFingerprint != envelope.optString("sender") ||
            frame.recipientFingerprint != keys.createOrLoad().fingerprint ||
            !publicNostr.verifyRemoteContact(peerCard, frame.senderFingerprint,
                encoder.encodeToString(frame.senderPublicKey), eventPublic)) return
        // All remaining frame order, relation identity, signatures and role
        // restrictions are independently checked by LocalPairingManager.scan.
        val before = signed.phase
        val after = pairings.scan(encoded).session ?: return
        if (state.peerCard == null) save(state.copy(peerCard = peerCard.toString()))
        if (before != after.phase) {
            onChanged()
            if (after.phase == PairingPhase.REVIEW_GRANT) {
                // The subject has already approved the human safety-code
                // comparison; send only the helper's signed ACK automatically.
                approve()
            } else if (after.phase == PairingPhase.COMPLETE &&
                state.role == PairingRole.SUBJECT) {
                publishReceipt(state, after.ack)
            }
        } else if (after.phase == PairingPhase.COMPLETE &&
            state.role == PairingRole.SUBJECT && step == "ACK") {
            // Lost receipt: a duplicate signed ACK is safe and elicits a new receipt.
            publishReceipt(state, after.ack)
        }
    }

    private fun publishFrame(state: Session, encoded: String): Boolean {
        val signed = PairingQrProtocol.decodeAndVerify(encoded,
            System.currentTimeMillis()).frame
        val contact = state.peerCard?.let { JSONObject(it) }
            ?: error("尚未获得对方的签名公共节点名片")
        return publish(state, signed.step.name, signed.recipientFingerprint,
            contact, JSONObject().put("frame", encoded).toString())
    }

    private fun publishReceipt(state: Session, ack: String): Boolean {
        if (ack.isBlank()) return false
        val session = pairings.snapshot().session ?: return false
        val response = PairingQrProtocol.decodeAndVerify(session.response,
            System.currentTimeMillis()).frame
        val digest = PairingQrProtocol.fingerprint(ack.toByteArray(Charsets.UTF_8))
        val content = JSONObject().put("v", 1).put("ack", digest)
            .put("sig", encoder.encodeToString(keys.signRemotePairingReceipt(digest)))
        val card = state.peerCard?.let { JSONObject(it) } ?: return false
        return publish(state, "RECEIPT", response.senderFingerprint, card, content.toString())
    }

    private fun publishDone(state: Session, ack: String): Boolean {
        if (ack.isBlank()) return false
        val session = pairings.snapshot().session ?: return false
        val inviter = PairingQrProtocol.decodeAndVerify(session.invite,
            System.currentTimeMillis()).frame
        val digest = PairingQrProtocol.fingerprint(ack.toByteArray(Charsets.UTF_8))
        val content = JSONObject().put("v", 1).put("ack", digest)
            .put("sig", encoder.encodeToString(keys.signRemotePairingDone(digest)))
        val contact = state.peerCard?.let { JSONObject(it) } ?: return false
        return publish(state, "DONE", inviter.senderFingerprint, contact, content.toString())
    }

    private fun publish(state: Session, step: String, recipient: String,
                        contact: JSONObject, plaintext: String): Boolean {
        val aad = RemotePairingInvitationCodec.aad(state.room, step, state.expiresAt)
        // Even fingerprints and signed contact cards stay inside the encrypted
        // envelope; public nodes only see a random mailbox and Nostr event ID.
        val envelope = JSONObject().put("v", 1)
            .put("sender", keys.createOrLoad().fingerprint)
            .put("recipient", recipient)
            .put("card", publicNostr.ownRemoteContactCard())
            .put("payload", JSONObject(plaintext))
        val box = publicNostr.sealRemotePairing(envelope.toString()
            .toByteArray(Charsets.UTF_8), contact.getString("epub"), aad)
        val payload = JSONObject().put("type", "remote-pairing").put("room", state.room)
            .put("step", step).put("exp", state.expiresAt).put("box", box)
        return publicNostr.publishRemotePairing(state.room, payload, state.expiresAt)
    }

    private fun save(state: Session) {
        val json = JSONObject().put("link", state.link).put("room", state.room)
            .put("role", state.role.name).put("exp", state.expiresAt)
            .put("peerFinished", state.peerFinished)
        state.peerCard?.let { json.put("peerCard", it) }
        check(prefs.edit().putString("session", json.toString()).commit()) {
            "无法持久化当前远程配对状态"
        }
    }

    private fun load(): Session? = runCatching {
        val raw = prefs.getString("session", null) ?: return@runCatching null
        val json = JSONObject(raw)
        Session(json.getString("link"), json.getString("room"),
            PairingRole.valueOf(json.getString("role")), json.optString("peerCard")
                .takeIf { it.isNotEmpty() }, json.getLong("exp"),
            json.optBoolean("peerFinished", false))
    }.getOrNull()
}
