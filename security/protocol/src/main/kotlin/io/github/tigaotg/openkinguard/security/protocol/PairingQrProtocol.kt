package io.github.tigaotg.openkinguard.security.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.AlgorithmParameters
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

enum class PairingStep { INVITE, RESPONSE, GRANT, ACK }

/** Only two least-privileged capabilities are initially offered; broader permissions require later consent. */
object InitialPairingPermissions {
    const val RECEIVE_ALERTS = 1
    const val RESPOND_TO_ALERTS = 2
    const val DEFAULT = RECEIVE_ALERTS or RESPOND_TO_ALERTS
}

data class PairingFrame(
    val step: PairingStep,
    val sessionId: String,
    val relationId: String,
    val senderPublicKey: ByteArray,
    val recipientFingerprint: String,
    val previousFrameHash: String,
    val permissionBits: Int,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    val senderFingerprint: String get() = PairingQrProtocol.fingerprint(senderPublicKey)
}

data class VerifiedPairingQr(val frame: PairingFrame, val encodedQr: String) {
    val hash: String get() = PairingQrProtocol.fingerprint(encodedQr.toByteArray(Charsets.UTF_8))
}

/** Self-authenticating QR frames. Physical confirmation is still REQUIRED: a valid signature is not a trusted person. */
object PairingQrProtocol {
    private const val PREFIX = "okg-pair-v1:"
    private const val MAGIC = 0x4f4b4731
    private const val MAX_QR_CHARS = 2_400
    private const val MAX_FRAME_BYTES = 1_400
    private const val MAX_TTL_MILLIS = 10 * 60 * 1_000L
    private const val ALLOWED_SKEW_MILLIS = 60_000L

    fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun publicKey(encoded: ByteArray): PublicKey {
        require(encoded.size in 70..200) { "Invalid public key length" }
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) as ECPublicKey
        val expected = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        require(key.params.order == expected.order && key.params.generator == expected.generator &&
            key.params.curve == expected.curve) { "Expected P-256 public key" }
        return key
    }

    fun encode(frame: PairingFrame, privateKey: PrivateKey): String {
        validateShape(frame)
        val bytes = serialize(frame)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(bytes)
            sign()
        }
        val result = PREFIX + base64(bytes) + "." + base64(signature)
        require(result.length <= MAX_QR_CHARS) { "QR too large" }
        return result
    }

    fun decodeAndVerify(encoded: String, nowEpochMillis: Long): VerifiedPairingQr {
        require(encoded.length in 50..MAX_QR_CHARS && encoded.startsWith(PREFIX)) { "Not an OpenKinGuard QR" }
        val parts = encoded.removePrefix(PREFIX).split('.')
        require(parts.size == 2) { "Malformed signed QR" }
        val bytes = unbase64(parts[0]); val signature = unbase64(parts[1])
        require(bytes.size <= MAX_FRAME_BYTES && signature.size in 64..80) { "Unreasonable QR size" }
        val frame = deserialize(bytes)
        validateShape(frame)
        val validSignature = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(frame.senderPublicKey))
            update(bytes)
            verify(signature)
        }
        require(validSignature) { "QR signature invalid" }
        require(nowEpochMillis < frame.expiresAtEpochMillis &&
            nowEpochMillis >= frame.createdAtEpochMillis - ALLOWED_SKEW_MILLIS) {
            "QR expired or phone time incorrect"
        }
        return VerifiedPairingQr(frame, encoded)
    }

    /** Ensure four-step signed, exact-chain, direction-specific consent; reject wrong/foreign QR. */
    fun requireNext(prior: VerifiedPairingQr, next: VerifiedPairingQr) {
        val a = prior.frame; val b = next.frame
        require(b.step.ordinal == a.step.ordinal + 1) { "Unexpected pairing step" }
        require(b.sessionId == a.sessionId && b.relationId == a.relationId) { "Different pairing session" }
        require(b.createdAtEpochMillis == a.createdAtEpochMillis &&
            b.expiresAtEpochMillis == a.expiresAtEpochMillis) { "Pairing time was changed" }
        require(b.permissionBits == a.permissionBits) { "Permissions were changed" }
        require(b.previousFrameHash == prior.hash) { "QR does not respond to the previous step" }
        require(b.recipientFingerprint == a.senderFingerprint) { "QR addressed to a different device" }
    }

    fun newFrame(
        step: PairingStep,
        ownPublicKey: ByteArray,
        prior: VerifiedPairingQr? = null,
        sessionId: String = UUID.randomUUID().toString(),
        relationId: String = UUID.randomUUID().toString(),
        nowEpochMillis: Long,
    ): PairingFrame = if (prior == null) {
        require(step == PairingStep.INVITE)
        PairingFrame(step, sessionId, relationId, ownPublicKey, "", "",
            InitialPairingPermissions.DEFAULT, nowEpochMillis, nowEpochMillis + MAX_TTL_MILLIS)
    } else PairingFrame(step, prior.frame.sessionId, prior.frame.relationId,
        ownPublicKey, prior.frame.senderFingerprint, prior.hash,
        prior.frame.permissionBits, prior.frame.createdAtEpochMillis,
        prior.frame.expiresAtEpochMillis)

    private fun validateShape(frame: PairingFrame) {
        require(UUID.fromString(frame.sessionId).toString() == frame.sessionId)
        require(UUID.fromString(frame.relationId).toString() == frame.relationId)
        require(frame.permissionBits == InitialPairingPermissions.DEFAULT) { "Unsupported permissions" }
        require(frame.createdAtEpochMillis >= 0 && frame.expiresAtEpochMillis > frame.createdAtEpochMillis &&
            frame.expiresAtEpochMillis - frame.createdAtEpochMillis <= MAX_TTL_MILLIS)
        require(frame.senderPublicKey.size in 70..200)
        require(frame.recipientFingerprint.isEmpty() || frame.recipientFingerprint.matches(Regex("[a-f0-9]{64}")))
        require(frame.previousFrameHash.isEmpty() || frame.previousFrameHash.matches(Regex("[a-f0-9]{64}")))
        if (frame.step == PairingStep.INVITE) {
            require(frame.recipientFingerprint.isEmpty() && frame.previousFrameHash.isEmpty())
        } else require(frame.recipientFingerprint.isNotEmpty() && frame.previousFrameHash.isNotEmpty())
    }

    private fun serialize(frame: PairingFrame): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(MAGIC)
            it.writeByte(frame.step.ordinal)
            it.writeUTF(frame.sessionId)
            it.writeUTF(frame.relationId)
            it.writeShort(frame.senderPublicKey.size)
            it.write(frame.senderPublicKey)
            it.writeUTF(frame.recipientFingerprint)
            it.writeUTF(frame.previousFrameHash)
            it.writeInt(frame.permissionBits)
            it.writeLong(frame.createdAtEpochMillis)
            it.writeLong(frame.expiresAtEpochMillis)
        }
        return out.toByteArray()
    }

    private fun deserialize(bytes: ByteArray): PairingFrame = DataInputStream(ByteArrayInputStream(bytes)).use {
        require(it.readInt() == MAGIC) { "Unknown pairing format" }
        val step = PairingStep.entries.getOrNull(it.readUnsignedByte())
            ?: throw IllegalArgumentException("Invalid pairing step")
        val sessionId = it.readUTF()
        val relationId = it.readUTF()
        val keyLength = it.readUnsignedShort()
        require(keyLength in 70..200) { "Invalid key length" }
        val key = ByteArray(keyLength).also(it::readFully)
        val recipient = it.readUTF()
        val previous = it.readUTF()
        val permissionBits = it.readInt()
        val created = it.readLong()
        val expires = it.readLong()
        require(it.available() == 0) { "Unexpected extra QR bytes" }
        PairingFrame(step, sessionId, relationId, key, recipient, previous, permissionBits, created, expires)
    }

    private fun base64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unbase64(text: String) = Base64.getUrlDecoder().decode(text)
}
