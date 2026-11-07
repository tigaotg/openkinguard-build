package io.github.tigaotg.openkinguard

import android.content.Context
import android.util.AtomicFile
import io.github.tigaotg.openkinguard.core.engine.DirectedPeerBinding
import io.github.tigaotg.openkinguard.core.engine.PairingIdentityPolicy
import io.github.tigaotg.openkinguard.security.protocol.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.Base64
import java.security.MessageDigest

enum class PairingRole { SUBJECT, HELPER }
enum class PairingPhase {
    WAIT_RESPONSE, REVIEW_INVITE, WAIT_GRANT, REVIEW_RESPONSE,
    WAIT_ACK, REVIEW_GRANT, ACK_TO_SHOW, COMPLETE,
}

data class PairingSession(
    val role: PairingRole,
    val phase: PairingPhase,
    val invite: String,
    val response: String = "",
    val grant: String = "",
    val ack: String = "",
)

data class SavedPeer(
    val relationId: String,
    val peerFingerprint: String,
    val peerPublicKeyBase64: String,
    val role: PairingRole,
    val permissionBits: Int,
    val active: Boolean = true,
)

data class PairingSnapshot(
    val session: PairingSession? = null,
    val peers: List<SavedPeer> = emptyList(),
    /** Short-lived cancellation tombstones prevent delayed LAN packets reviving cancelled invites. */
    val cancelledSessions: Map<String, Long> = emptyMap(),
    /** Family-relay route is persisted so an in-flight signed pairing can resume after process death. */
    val familyRelayPeer: FamilyRelayPeer? = null,
)

internal object FamilyRelayPeerCodec {
    private val fingerprintPattern = Regex("[0-9a-f]{64}")

    fun validate(peer: FamilyRelayPeer) {
        require(fingerprintPattern.matches(peer.fingerprint)) { "家庭设备指纹格式无效" }
        require(peer.slot in 0..2) { "家庭中继节点编号无效" }
    }

    fun encode(peer: FamilyRelayPeer): JSONObject {
        validate(peer)
        return JSONObject().put("fingerprint", peer.fingerprint).put("slot", peer.slot)
    }

    fun decode(value: JSONObject): FamilyRelayPeer {
        val peer = FamilyRelayPeer(value.getString("fingerprint"), value.getInt("slot"))
        validate(peer)
        return peer
    }
}

/** Atomically written, private, non-backed-up public trust records. Corruption blocks pairing. */
private class PairingFileStore(context: Context) {
    companion object {
        private val LOCK = Any()
        private var cachePath: String? = null
        private var cacheModified = -1L
        private var cacheLength = -1L
        private var cache: PairingSnapshot? = null
    }
    private val dataFile = File(context.filesDir, "s1-pairing-v1.json")
    private val file = AtomicFile(dataFile)

    /** Avoid re-parsing the whole pairing JSON on every Compose heartbeat repaint. */
    fun load(): PairingSnapshot = synchronized(LOCK) {
        if (cachePath == dataFile.absolutePath && cache != null &&
            cacheModified == dataFile.lastModified() && cacheLength == dataFile.length()) cache!!
        else loadDisk().also { value ->
            cachePath = dataFile.absolutePath
            cacheModified = dataFile.lastModified()
            cacheLength = dataFile.length()
            cache = value
        }
    }

    private fun loadDisk(): PairingSnapshot = try {
        val root = JSONObject(String(file.readFully(), Charsets.UTF_8))
        require(root.getInt("schema") == 1) { "Unsupported stored pairing schema" }
        val session = root.optJSONObject("session")?.let {
            PairingSession(
                role = PairingRole.valueOf(it.getString("role")),
                phase = PairingPhase.valueOf(it.getString("phase")),
                invite = it.getString("invite"),
                response = it.optString("response"),
                grant = it.optString("grant"),
                ack = it.optString("ack"),
            )
        }
        val array = root.getJSONArray("peers")
        val peers = (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            SavedPeer(item.getString("relationId"), item.getString("fingerprint"),
                item.getString("publicKey"), PairingRole.valueOf(item.getString("role")),
                item.getInt("permissions"), item.getBoolean("active"))
        }
        val cancelled = root.optJSONObject("cancelledSessions")?.let { entries ->
            entries.keys().asSequence().associateWith { entries.getLong(it) }
        }.orEmpty()
        val familyRelayPeer = root.optJSONObject("familyRelayPeer")?.let(FamilyRelayPeerCodec::decode)
        PairingSnapshot(session, peers, cancelled, familyRelayPeer)
    } catch (_: FileNotFoundException) { PairingSnapshot() }

    fun save(data: PairingSnapshot) = synchronized(LOCK) {
        val root = JSONObject().put("schema", 1)
        data.session?.let { s ->
            root.put("session", JSONObject()
                .put("role", s.role.name).put("phase", s.phase.name)
                .put("invite", s.invite).put("response", s.response)
                .put("grant", s.grant).put("ack", s.ack))
        }
        val array = JSONArray()
        data.peers.forEach { peer ->
            array.put(JSONObject().put("relationId", peer.relationId)
                .put("fingerprint", peer.peerFingerprint)
                .put("publicKey", peer.peerPublicKeyBase64)
                .put("role", peer.role.name)
                .put("permissions", peer.permissionBits)
                .put("active", peer.active))
        }
        root.put("peers", array)
        root.put("cancelledSessions", JSONObject(data.cancelledSessions))
        data.familyRelayPeer?.let { root.put("familyRelayPeer", FamilyRelayPeerCodec.encode(it)) }
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            if (stream != null) file.failWrite(stream)
            throw error
        }
        cachePath = dataFile.absolutePath
        cacheModified = dataFile.lastModified()
        cacheLength = dataFile.length()
        cache = data
    }
}

/** Only signed QR exchange; not a network transport or a production pairing service yet. */
class LocalPairingManager(context: Context) {
    private val storage = PairingFileStore(context)
    private val keys = AndroidDeviceKeyStore()

    fun snapshot(): PairingSnapshot = storage.load()

    @Synchronized
    fun setFamilyRelayPeer(peer: FamilyRelayPeer?) {
        peer?.let(FamilyRelayPeerCodec::validate)
        store(storage.load().copy(familyRelayPeer = peer))
    }

    /** Legacy duplicates remain visible until the owner explicitly chooses which one to retain. */
    fun legacyDuplicateGroups(): List<List<SavedPeer>> {
        val peers = storage.load().peers
        val groups = PairingIdentityPolicy.duplicateGroups(peers.map { it.toBinding() })
        return groups.map { group ->
            val ids = group.map { it.relationId }.toSet()
            peers.filter { it.relationId in ids && it.active }
        }
    }

    @Synchronized
    fun keepOneLegacyRelation(keepRelationId: String): List<String> {
        val current = storage.load()
        val keep = current.peers.singleOrNull { it.active && it.relationId == keepRelationId }
            ?: throw IllegalArgumentException("要保留的关系不存在或已撤销")
        val duplicates = current.peers.filter { it.active &&
            it.peerFingerprint == keep.peerFingerprint && it.role == keep.role }
        require(duplicates.size > 1) { "这组设备关系不再存在重复" }
        val removed = duplicates.filter { it.relationId != keepRelationId }
            .map { it.relationId }
        store(current.copy(peers = current.peers.map {
            if (it.relationId in removed) it.copy(active = false) else it
        }))
        return removed // Old public-key records remain stored, never erased.
    }

    private fun SavedPeer.toBinding() = DirectedPeerBinding(
        relationId, peerFingerprint, role.name, active)

    private fun requireUniqueRole(current: PairingSnapshot, fingerprint: String,
                                  role: PairingRole, exemptRelationId: String? = null) {
        val conflict = PairingIdentityPolicy.conflictingRelation(current.peers.map { it.toBinding() },
            fingerprint, role.name, exemptRelationId)
        require(conflict == null) {
            "该设备指纹已经建立此方向的帮助关系（${conflict?.take(8)}），不能重复添加；" +
                "如需更换，请先在双方设备撤销旧关系"
        }
    }

    /** Identical on both devices after RESPONSE. Human comparison is mandatory for LAN pairing. */
    fun safetyCode(): String? {
        val session = storage.load().session ?: return null
        if (session.response.isEmpty()) return null
        val invite = decode(session.invite).frame
        val response = decode(session.response).frame
        PairingQrProtocol.requireNext(decode(session.invite), decode(session.response))
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update("OpenKinGuard|LAN|human-compare|v1|".toByteArray(Charsets.UTF_8))
        hash.update(invite.senderPublicKey)
        hash.update(response.senderPublicKey)
        hash.update(invite.relationId.toByteArray(Charsets.UTF_8))
        val bytes = hash.digest()
        val value = ((bytes[0].toLong() and 255L) shl 24) or
            ((bytes[1].toLong() and 255L) shl 16) or
            ((bytes[2].toLong() and 255L) shl 8) or (bytes[3].toLong() and 255L)
        return (value % 100_000_000L).toString().padStart(8, '0')
    }

    @Synchronized
    fun createInvitation(familyRelayPeer: FamilyRelayPeer? = null): PairingSnapshot {
        val current = storage.load()
        require(current.session == null) { "Finish or cancel the existing pairing first" }
        familyRelayPeer?.let(FamilyRelayPeerCodec::validate)
        val frame = PairingQrProtocol.newFrame(PairingStep.INVITE,
            keys.publicKeyBytes(), nowEpochMillis = System.currentTimeMillis())
        return store(current.copy(session = PairingSession(PairingRole.SUBJECT,
            PairingPhase.WAIT_RESPONSE, keys.signPairingFrame(frame)),
            familyRelayPeer = familyRelayPeer))
    }

    /** Receipt is verified but DOES NOT constitute user consent. */
    @Synchronized
    fun scan(rawQr: String, familyRelayPeer: FamilyRelayPeer? = null): PairingSnapshot {
        val current = storage.load()
        familyRelayPeer?.let(FamilyRelayPeerCodec::validate)
        val incoming = PairingQrProtocol.decodeAndVerify(rawQr.trim(), System.currentTimeMillis())
        require(current.cancelledSessions[incoming.frame.sessionId]?.let {
            it > System.currentTimeMillis()
        } != true) { "本轮绑定已取消，不能恢复旧邀请" }
        val localFingerprint = keys.createOrLoad().fingerprint
        val existing = current.session
        // The network receipt may get lost after a valid frame was persisted. An exact,
        // signature-verified retransmission is a no-op in EVERY later phase, including COMPLETE.
        if (existing != null && listOf(existing.invite, existing.response, existing.grant,
                existing.ack).any { it.isNotBlank() && it == incoming.encodedQr }) {
            return if (familyRelayPeer != null && current.familyRelayPeer != familyRelayPeer)
                store(current.copy(familyRelayPeer = familyRelayPeer)) else current
        }
        val next = when (existing?.phase) {
            null -> {
                require(incoming.frame.step == PairingStep.INVITE) { "Begin by scanning an invitation" }
                require(incoming.frame.senderFingerprint != localFingerprint) { "Cannot pair a device with itself" }
                requireUniqueRole(current, incoming.frame.senderFingerprint, PairingRole.HELPER)
                PairingSession(PairingRole.HELPER, PairingPhase.REVIEW_INVITE, incoming.encodedQr)
            }
            PairingPhase.WAIT_RESPONSE -> {
                val invite = decode(existing.invite)
                PairingQrProtocol.requireNext(invite, incoming)
                require(incoming.frame.step == PairingStep.RESPONSE)
                require(incoming.frame.recipientFingerprint == localFingerprint)
                require(incoming.frame.senderFingerprint != localFingerprint)
                requireUniqueRole(current, incoming.frame.senderFingerprint, PairingRole.SUBJECT)
                existing.copy(phase = PairingPhase.REVIEW_RESPONSE, response = incoming.encodedQr)
            }
            PairingPhase.WAIT_GRANT -> {
                val invite = decode(existing.invite)
                val response = decode(existing.response)
                PairingQrProtocol.requireNext(response, incoming)
                require(incoming.frame.step == PairingStep.GRANT)
                require(incoming.frame.senderFingerprint == invite.frame.senderFingerprint)
                require(incoming.frame.recipientFingerprint == localFingerprint)
                requireUniqueRole(current, incoming.frame.senderFingerprint, PairingRole.HELPER)
                existing.copy(phase = PairingPhase.REVIEW_GRANT, grant = incoming.encodedQr)
            }
            PairingPhase.WAIT_ACK -> {
                val response = decode(existing.response)
                val grant = decode(existing.grant)
                PairingQrProtocol.requireNext(grant, incoming)
                require(incoming.frame.step == PairingStep.ACK)
                require(incoming.frame.senderFingerprint == response.frame.senderFingerprint)
                require(incoming.frame.recipientFingerprint == localFingerprint)
                val finished = existing.copy(phase = PairingPhase.COMPLETE, ack = incoming.encodedQr)
                return store(appendPeer(current.copy(session = finished), response,
                    PairingRole.SUBJECT))
            }
            else -> throw IllegalArgumentException("Current screen does not expect a scanned QR")
        }
        return store(current.copy(session = next,
            familyRelayPeer = familyRelayPeer ?: current.familyRelayPeer))
    }

    /** Explicit local approval at EACH trust-establishing step. */
    @Synchronized
    fun confirm(): PairingSnapshot {
        val current = storage.load()
        val session = requireNotNull(current.session) { "No pairing in progress" }
        val invite = decode(session.invite)
        val local = keys.createOrLoad().fingerprint
        return when (session.phase) {
            PairingPhase.REVIEW_INVITE -> {
                require(local != invite.frame.senderFingerprint)
                requireUniqueRole(current, invite.frame.senderFingerprint, PairingRole.HELPER)
                val frame = PairingQrProtocol.newFrame(PairingStep.RESPONSE,
                    keys.publicKeyBytes(), invite, nowEpochMillis = System.currentTimeMillis())
                store(current.copy(session = session.copy(phase = PairingPhase.WAIT_GRANT,
                    response = keys.signPairingFrame(frame))))
            }
            PairingPhase.REVIEW_RESPONSE -> {
                val response = decode(session.response)
                PairingQrProtocol.requireNext(invite, response)
                require(invite.frame.senderFingerprint == local)
                requireUniqueRole(current, response.frame.senderFingerprint, PairingRole.SUBJECT)
                val frame = PairingQrProtocol.newFrame(PairingStep.GRANT,
                    keys.publicKeyBytes(), response, nowEpochMillis = System.currentTimeMillis())
                store(current.copy(session = session.copy(phase = PairingPhase.WAIT_ACK,
                    grant = keys.signPairingFrame(frame))))
            }
            PairingPhase.REVIEW_GRANT -> {
                val response = decode(session.response)
                val grant = decode(session.grant)
                PairingQrProtocol.requireNext(response, grant)
                require(response.frame.senderFingerprint == local &&
                    grant.frame.senderFingerprint == invite.frame.senderFingerprint)
                requireUniqueRole(current, invite.frame.senderFingerprint, PairingRole.HELPER)
                val frame = PairingQrProtocol.newFrame(PairingStep.ACK,
                    keys.publicKeyBytes(), grant, nowEpochMillis = System.currentTimeMillis())
                val finalSession = session.copy(phase = PairingPhase.ACK_TO_SHOW,
                    ack = keys.signPairingFrame(frame))
                // Only pin when the helper explicitly confirms the subject has finished.
                // Cancelling an unacknowledged final packet must not create a phantom relation.
                store(current.copy(session = finalSession))
            }
            else -> throw IllegalArgumentException("Not waiting for a local confirmation")
        }
    }

    @Synchronized
    fun finishHelperAfterAckShown(): PairingSnapshot {
        val current = storage.load()
        val session = requireNotNull(current.session)
        require(session.role == PairingRole.HELPER && session.phase == PairingPhase.ACK_TO_SHOW)
        // Explicit human confirmation after the subject's display shows pairing completed.
        val invite = decode(session.invite)
        return store(appendPeer(current.copy(session = session.copy(phase = PairingPhase.COMPLETE)),
            invite, PairingRole.HELPER))
    }

    @Synchronized
    fun cancelPending(): PairingSnapshot {
        val current = storage.load()
        // Already-pinned relations are retained; cancellation cannot silently revoke them.
        val now = System.currentTimeMillis()
        val stillValid = current.cancelledSessions.filterValues { it > now }
        val pending = current.session?.takeIf { it.phase != PairingPhase.COMPLETE }
        val signed = pending?.let { runCatching { decode(it.invite).frame }.getOrNull() }
        val cancelled = if (signed == null) stillValid else {
            (stillValid + (signed.sessionId to signed.expiresAtEpochMillis))
                .entries.sortedByDescending { it.value }.take(32)
                .associate { it.key to it.value }
        }
        return store(current.copy(session = null, cancelledSessions = cancelled,
            familyRelayPeer = null))
    }

    @Synchronized
    fun revoke(relationId: String): PairingSnapshot {
        val current = storage.load()
        require(current.peers.any { it.relationId == relationId && it.active })
        return store(current.copy(peers = current.peers.map { peer ->
            if (peer.relationId == relationId) peer.copy(active = false) else peer
        }))
    }

    private fun appendPeer(snapshot: PairingSnapshot, peerFrame: VerifiedPairingQr,
                           role: PairingRole): PairingSnapshot {
        val existing = snapshot.peers.find { it.relationId == peerFrame.frame.relationId }
        require(existing == null) { "Relationship ID is already in use" }
        requireUniqueRole(snapshot, peerFrame.frame.senderFingerprint, role)
        val newPeer = SavedPeer(peerFrame.frame.relationId, peerFrame.frame.senderFingerprint,
            Base64.getUrlEncoder().withoutPadding().encodeToString(peerFrame.frame.senderPublicKey),
            role, peerFrame.frame.permissionBits)
        return snapshot.copy(peers = snapshot.peers + newPeer, familyRelayPeer = null)
    }

    private fun decode(text: String): VerifiedPairingQr =
        PairingQrProtocol.decodeAndVerify(text, System.currentTimeMillis())

    private fun store(value: PairingSnapshot): PairingSnapshot { storage.save(value); return value }
}
