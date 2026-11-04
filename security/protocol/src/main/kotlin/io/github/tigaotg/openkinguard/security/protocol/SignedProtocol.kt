package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/** Wire security contract v1. No Android runtime or transport dependency. */
object SignedProtocol {
    private val domain = "OpenKinGuard|signed-envelope|v1".toByteArray(Charsets.US_ASCII)
    private const val MAX_PAYLOAD_BYTES = 65_536
    private const val MAX_FIELD_BYTES = 512

    /** A deterministic binary encoding with length prefixes, for signature input only. */
    fun bytesToSign(message: MessageEnvelope): ByteArray {
        require(message.protocolVersion == 1) { "Unsupported protocol version" }
        require(message.payload.size <= MAX_PAYLOAD_BYTES) { "Payload too large" }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(domain.size)
            out.write(domain)
            out.writeInt(message.protocolVersion)
            out.utf8(message.messageId.value)
            out.utf8(message.eventId.value)
            out.utf8(message.senderDeviceId.value)
            out.utf8(message.subjectId.value)
            out.utf8(message.recipientDeviceId.value)
            out.writeLong(message.createdEpochMillis)
            out.writeLong(message.expiresEpochMillis)
            out.writeLong(message.sequence)
            out.utf8(message.payloadKind)
            out.writeInt(message.payload.size)
            out.write(message.payload)
        }
        return buffer.toByteArray()
    }

    private fun DataOutputStream.utf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_FIELD_BYTES) { "Invalid field size" }
        writeInt(bytes.size)
        write(bytes)
    }

    fun sign(message: MessageEnvelope, privateKey: PrivateKey): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(bytesToSign(message))
            sign()
        }

    fun verify(message: MessageEnvelope, signature: ByteArray, trustedPublicKey: PublicKey): Boolean {
        if (signature.isEmpty() || signature.size > 512) return false
        return try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(trustedPublicKey)
                update(bytesToSign(message))
                verify(signature)
            }
        } catch (_: Exception) { false }
    }
}

data class SignedEnvelope(val message: MessageEnvelope, val signature: ByteArray)

enum class AdmissionFailure {
    WRONG_SENDER, WRONG_RECIPIENT, UNTRUSTED_KEY, BAD_SIGNATURE,
    EXPIRED, FUTURE_TIMESTAMP, DUPLICATE, SEQUENCE_TOO_OLD,
}

data class ReplayWindow(
    val greatestSequence: Long = -1,
    val receivedBits: Long = 0,
    val recentMessageIds: List<MessageId> = emptyList(),
)

sealed interface AdmissionResult {
    data class Accepted(val nextWindow: ReplayWindow) : AdmissionResult
    data class Rejected(val reason: AdmissionFailure) : AdmissionResult
}

/**
 * Caller MUST supply a locally pinned, authorized public key from a mutually
 * approved relationship. Never trust a public key sent alongside an unpaired message.
 * The caller MUST durably commit nextWindow before any incident side effects.
 */
object AuthenticatedAdmission {
    fun examine(
        signed: SignedEnvelope,
        expectedSender: DeviceId,
        expectedRecipient: DeviceId,
        trustedPublicKey: PublicKey?,
        nowEpochMillis: Long,
        previousWindow: ReplayWindow,
        allowedFutureSkewMillis: Long = 30_000,
    ): AdmissionResult {
        val msg = signed.message
        if (msg.senderDeviceId != expectedSender) return AdmissionResult.Rejected(AdmissionFailure.WRONG_SENDER)
        if (msg.recipientDeviceId != expectedRecipient) return AdmissionResult.Rejected(AdmissionFailure.WRONG_RECIPIENT)
        if (trustedPublicKey == null) return AdmissionResult.Rejected(AdmissionFailure.UNTRUSTED_KEY)
        if (!SignedProtocol.verify(msg, signed.signature, trustedPublicKey)) {
            return AdmissionResult.Rejected(AdmissionFailure.BAD_SIGNATURE)
        }
        if (nowEpochMillis >= msg.expiresEpochMillis) return AdmissionResult.Rejected(AdmissionFailure.EXPIRED)
        if (msg.createdEpochMillis > nowEpochMillis + allowedFutureSkewMillis) {
            return AdmissionResult.Rejected(AdmissionFailure.FUTURE_TIMESTAMP)
        }
        if (msg.messageId in previousWindow.recentMessageIds) {
            return AdmissionResult.Rejected(AdmissionFailure.DUPLICATE)
        }
        val greatest = previousWindow.greatestSequence
        val bits = previousWindow.receivedBits
        val newBits = if (msg.sequence > greatest) {
            val shift = if (greatest < 0) 64L else msg.sequence - greatest
            if (shift >= 64) 1L else (bits shl shift.toInt()) or 1L
        } else {
            val difference = greatest - msg.sequence
            if (difference >= 64) return AdmissionResult.Rejected(AdmissionFailure.SEQUENCE_TOO_OLD)
            val bit = 1L shl difference.toInt()
            if ((bits and bit) != 0L) return AdmissionResult.Rejected(AdmissionFailure.DUPLICATE)
            bits or bit
        }
        return AdmissionResult.Accepted(ReplayWindow(
            greatestSequence = maxOf(greatest, msg.sequence),
            receivedBits = newBits,
            recentMessageIds = (previousWindow.recentMessageIds + msg.messageId).takeLast(128),
        ))
    }
}
