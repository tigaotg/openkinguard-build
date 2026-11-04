package io.github.tigaotg.openkinguard.core.model

/** Distinct from MessageId: every retransmission gets its own attempt identifier. */
@JvmInline
value class AttemptId(val value: String) {
    init { require(value.isNotBlank()) }
}

/** Authentication and anti-replay are checked outside the pure decision engine. */
data class MessageEnvelope(
    val protocolVersion: Int,
    val messageId: MessageId,
    val eventId: EventId,
    val senderDeviceId: DeviceId,
    val subjectId: UserId,
    val recipientDeviceId: DeviceId,
    val createdEpochMillis: Long,
    val expiresEpochMillis: Long,
    val sequence: Long,
    val payloadKind: String,
    val payload: ByteArray
) {
    init {
        require(protocolVersion > 0)
        require(sequence >= 0)
        require(createdEpochMillis >= 0)
        require(expiresEpochMillis > createdEpochMillis)
        require(payloadKind.isNotBlank())
    }

    // ByteArray uses reference equality by default. Compare the full envelope and bytes;
    // MessageId is ONLY for logical de-duplication, not evidence of identical content.
    override fun equals(other: Any?): Boolean = other is MessageEnvelope &&
        protocolVersion == other.protocolVersion &&
        messageId == other.messageId &&
        eventId == other.eventId &&
        senderDeviceId == other.senderDeviceId &&
        subjectId == other.subjectId &&
        recipientDeviceId == other.recipientDeviceId &&
        createdEpochMillis == other.createdEpochMillis &&
        expiresEpochMillis == other.expiresEpochMillis &&
        sequence == other.sequence &&
        payloadKind == other.payloadKind &&
        payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + messageId.hashCode()
        result = 31 * result + eventId.hashCode()
        result = 31 * result + senderDeviceId.hashCode()
        result = 31 * result + subjectId.hashCode()
        result = 31 * result + recipientDeviceId.hashCode()
        result = 31 * result + createdEpochMillis.hashCode()
        result = 31 * result + expiresEpochMillis.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + payloadKind.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

enum class DeliveryPriority { EMERGENCY, STATE_CHANGE, NORMAL }
enum class DeliveryStatus { QUEUED, IN_FLIGHT, ACKNOWLEDGED, EXPIRED, FAILED }

data class OutboxEntry(
    val envelope: MessageEnvelope,
    val priority: DeliveryPriority,
    val status: DeliveryStatus,
    val attemptCount: Int = 0,
    val nextAttemptEpochMillis: Long? = null
) {
    init { require(attemptCount >= 0) }
}

/** Clearly distinguish a verified external acknowledgement from a local UI update. */
data class DeliveryReceipt(
    val messageId: MessageId,
    val remoteDeviceId: DeviceId,
    val verified: Boolean,
    val acknowledgedAtEpochMillis: Long
)
