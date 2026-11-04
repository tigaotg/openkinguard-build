package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.MessageEnvelope
import java.util.UUID

/**
 * Small signed control messages for an explicitly approved remote-assistance session.
 * Media and gestures MUST NOT be sent through the SOS/location request transport.
 * An ACCEPT message is not screen-capture permission or an active media session.
 */
object RemoteAssistWire {
    const val REQUEST = "OKG_REMOTE_ASSIST_REQUEST_V1"
    const val ACCEPT = "OKG_REMOTE_ASSIST_ACCEPT_V1"
    const val DECLINE = "OKG_REMOTE_ASSIST_DECLINE_V1"
    const val STOP = "OKG_REMOTE_ASSIST_STOP_V1"
    const val RECEIPT = "OKG_REMOTE_ASSIST_RECEIPT_V1"

    val requestKinds = setOf(REQUEST, ACCEPT, DECLINE, STOP)
    val kinds = requestKinds + RECEIPT

    data class Body(val relationId: String, val sessionId: String,
                    val respondingTo: String? = null)

    fun request(relationId: String, sessionId: String): ByteArray =
        "Q|${uuid(relationId)}|${uuid(sessionId)}".toByteArray(Charsets.US_ASCII)

    fun accept(relationId: String, sessionId: String): ByteArray =
        "Y|${uuid(relationId)}|${uuid(sessionId)}".toByteArray(Charsets.US_ASCII)

    fun decline(relationId: String, sessionId: String): ByteArray =
        "N|${uuid(relationId)}|${uuid(sessionId)}".toByteArray(Charsets.US_ASCII)

    fun stop(relationId: String, sessionId: String): ByteArray =
        "S|${uuid(relationId)}|${uuid(sessionId)}".toByteArray(Charsets.US_ASCII)

    fun receipt(relationId: String, sessionId: String, messageId: String): ByteArray =
        "A|${uuid(relationId)}|${uuid(sessionId)}|${uuid(messageId)}"
            .toByteArray(Charsets.US_ASCII)

    fun body(message: MessageEnvelope): Body {
        require(message.payloadKind in kinds) { "Unknown remote-assistance control" }
        require(message.payload.size in 75..150 && message.payload.all { it.toInt() in 32..126 }) {
            "Malformed remote-assistance payload"
        }
        val parts = message.payload.toString(Charsets.US_ASCII).split('|')
        val expectedPrefix = when (message.payloadKind) {
            REQUEST -> "Q"
            ACCEPT -> "Y"
            DECLINE -> "N"
            STOP -> "S"
            else -> "A"
        }
        require(parts.size == if (message.payloadKind == RECEIPT) 4 else 3) {
            "Malformed remote-assistance fields"
        }
        require(parts[0] == expectedPrefix) { "Wrong remote-assistance operation" }
        val relationId = uuid(parts[1])
        val sessionId = uuid(parts[2])
        require(message.eventId.value == sessionId) { "Wrong remote-assistance session ID" }
        return Body(relationId, sessionId,
            if (message.payloadKind == RECEIPT) uuid(parts[3]) else null)
    }

    private fun uuid(value: String): String {
        require(value.length == 36 && UUID.fromString(value).toString() == value) {
            "Non-canonical remote-assistance UUID"
        }
        return value
    }
}
