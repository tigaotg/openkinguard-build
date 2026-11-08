package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.MessageEnvelope
import io.github.tigaotg.openkinguard.core.model.MessageId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Bounded S4 E2EE framing. Routers see only relationship routing and an ECDH/AES-GCM box. */
object S4MessageWire {
    private const val MAX_PAYLOAD_BYTES = 512
    private const val DOMAIN = "OpenKinGuard|S4|case-message|v1"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    data class SealedPayload(
        val ephemeralPublicKey: String,
        val nonce: String,
        val ciphertext: String,
    )
    data class Envelope(val relationId: String, val sealed: SealedPayload)

    fun encode(relationId: String, sealed: SealedPayload): ByteArray {
        requireUuid(relationId)
        validateSealed(sealed)
        val wire = "OKG4|1|" +
            encoder.encodeToString(relationId.toByteArray(Charsets.US_ASCII)) + "|" +
            sealed.ephemeralPublicKey + "|" + sealed.nonce + "|" + sealed.ciphertext
        return wire.toByteArray(Charsets.US_ASCII).also {
            require(it.size in 200..MAX_PAYLOAD_BYTES) { "Encrypted case payload exceeds LAN bound" }
        }
    }

    fun decode(payload: ByteArray): Envelope {
        require(payload.size in 200..MAX_PAYLOAD_BYTES) { "Invalid encrypted case payload size" }
        val text = payload.toString(Charsets.US_ASCII)
        require(text.toByteArray(Charsets.US_ASCII).contentEquals(payload)) {
            "Encrypted case payload must be ASCII"
        }
        val fields = text.split('|')
        require(fields.size == 6 && fields[0] == "OKG4" && fields[1] == "1") {
            "Unsupported or malformed encrypted case payload"
        }
        val relationBytes = decoder.decode(fields[2])
        val relation = String(relationBytes, Charsets.US_ASCII)
        require(relationBytes.contentEquals(relation.toByteArray(Charsets.US_ASCII)))
        requireUuid(relation)
        val sealed = SealedPayload(fields[3], fields[4], fields[5])
        validateSealed(sealed)
        return Envelope(relation, sealed)
    }

    /** A 32-byte digest binds ciphertext to every signed routing/header field. */
    fun associatedData(relationId: String, message: MessageEnvelope): ByteArray {
        requireUuid(relationId)
        val canonical = ByteArrayOutputStream()
        DataOutputStream(canonical).use { out ->
            out.writeUTF(DOMAIN)
            out.writeUTF(relationId)
            out.writeInt(message.protocolVersion)
            out.writeUTF(message.messageId.value)
            out.writeUTF(message.eventId.value)
            out.writeUTF(message.senderDeviceId.value)
            out.writeUTF(message.subjectId.value)
            out.writeUTF(message.recipientDeviceId.value)
            out.writeLong(message.createdEpochMillis)
            out.writeLong(message.expiresEpochMillis)
            out.writeLong(message.sequence)
            out.writeUTF(message.payloadKind)
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
    }

    /** Stable ID for one logical event, directed relationship, sender, recipient and kind. */
    fun stableMessageId(
        eventId: String,
        relationId: String,
        senderDeviceId: String,
        recipientDeviceId: String,
        payloadKind: String,
    ): MessageId {
        requireUuid(eventId)
        requireUuid(relationId)
        require(senderDeviceId.length in 1..96 && recipientDeviceId.length in 1..96)
        require(payloadKind.length in 1..96)
        val canonical = ByteArrayOutputStream()
        DataOutputStream(canonical).use { out ->
            out.writeUTF("OpenKinGuard|S4|stable-message-id|v1")
            out.writeUTF(eventId)
            out.writeUTF(relationId)
            out.writeUTF(senderDeviceId)
            out.writeUTF(recipientDeviceId)
            out.writeUTF(payloadKind)
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        return MessageId(UUID(buffer.long, buffer.long).toString())
    }

    private fun validateSealed(sealed: SealedPayload) {
        require(sealed.ephemeralPublicKey.length in 90..300)
        require(decoder.decode(sealed.ephemeralPublicKey).size in 70..200)
        require(sealed.nonce.length in 16..32 && decoder.decode(sealed.nonce).size == 12)
        require(sealed.ciphertext.length in 70..350)
        require(decoder.decode(sealed.ciphertext).size in 56..256)
    }

    private fun requireUuid(value: String) {
        require(value.length == 36 && UUID.fromString(value).toString() == value) {
            "Invalid S4 relationship or event UUID"
        }
    }
}
