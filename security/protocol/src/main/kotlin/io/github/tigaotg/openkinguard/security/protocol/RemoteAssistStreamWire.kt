package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.MessageEnvelope
import java.util.Base64
import java.util.UUID

/**
 * Strict, signed, session-scoped packets carried by the existing direct transport.
 * A frame is intentionally capped to small JPEG fragments; there is no media relay.
 * This module does not know about SOS, location or the user's long-lived pairings.
 */
object RemoteAssistStreamWire {
    const val FRAME = "OKG_REMOTE_FRAME_V1"
    const val TAP = "OKG_REMOTE_TAP_V1"
    const val SWIPE = "OKG_REMOTE_SWIPE_V1"
    const val BACK = "OKG_REMOTE_BACK_V1"
    const val HOME = "OKG_REMOTE_HOME_V1"
    const val ACK = "OKG_REMOTE_STREAM_ACK_V1"
    val requestKinds = setOf(FRAME, TAP, SWIPE, BACK, HOME)

    data class Packet(
        val relationId: String,
        val sessionId: String,
        val frameId: String? = null,
        val part: Int = 0,
        val total: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        val jpegPart: ByteArray = byteArrayOf(),
        val x: Int = 0,
        val y: Int = 0,
        val x2: Int = 0,
        val y2: Int = 0,
        val durationMs: Int = 0,
        val respondsTo: String? = null,
    )

    fun frame(relationId: String, sessionId: String, frameId: String,
              part: Int, total: Int, width: Int, height: Int, jpegPart: ByteArray): ByteArray {
        require(part in 0 until total && total in 1..40)
        require(width in 100..1500 && height in 100..3000)
        require(jpegPart.size in 1..2300)
        return "F|${uuid(relationId)}|${uuid(sessionId)}|${uuid(frameId)}|$part|$total|".plus(
            "$width|$height|${Base64.getUrlEncoder().withoutPadding().encodeToString(jpegPart)}")
            .toByteArray(Charsets.US_ASCII)
    }

    fun tap(relationId: String, sessionId: String, x: Int, y: Int): ByteArray {
        require(x in 0..10000 && y in 0..10000)
        return "T|${uuid(relationId)}|${uuid(sessionId)}|$x|$y"
            .toByteArray(Charsets.US_ASCII)
    }

    fun action(relationId: String, sessionId: String, kind: String): ByteArray {
        require(kind == BACK || kind == HOME)
        return "${if (kind == BACK) "B" else "H"}|${uuid(relationId)}|${uuid(sessionId)}"
            .toByteArray(Charsets.US_ASCII)
    }

    fun swipe(relationId: String, sessionId: String, x1: Int, y1: Int,
              x2: Int, y2: Int, durationMs: Int = 350): ByteArray {
        require(listOf(x1, y1, x2, y2).all { it in 0..10000 })
        require(durationMs in 120..1000)
        return "W|${uuid(relationId)}|${uuid(sessionId)}|$x1|$y1|$x2|$y2|$durationMs"
            .toByteArray(Charsets.US_ASCII)
    }

    fun ack(relationId: String, sessionId: String, respondsTo: String): ByteArray =
        "A|${uuid(relationId)}|${uuid(sessionId)}|${uuid(respondsTo)}"
            .toByteArray(Charsets.US_ASCII)

    fun decode(message: MessageEnvelope): Packet {
        require(message.payloadKind in requestKinds + ACK) { "Unknown assistance packet" }
        require(message.payload.size in 75..4300 &&
            message.payload.all { it.toInt() in 32..126 }) { "Invalid assistance packet" }
        val parts = message.payload.toString(Charsets.US_ASCII).split('|')
        val expected = when (message.payloadKind) {
            FRAME -> "F"; TAP -> "T"; SWIPE -> "W";
            BACK -> "B"; HOME -> "H"; else -> "A"
        }
        require(parts.firstOrNull() == expected) { "Assistance packet kind mismatch" }
        require(parts.size == when (message.payloadKind) {
            FRAME -> 9; TAP -> 5; SWIPE -> 8; BACK, HOME -> 3; else -> 4
        }) { "Invalid assistance packet fields" }
        val relation = uuid(parts[1]); val session = uuid(parts[2])
        require(message.eventId.value == session) { "Session mismatch" }
        return when (message.payloadKind) {
            FRAME -> {
                val frame = uuid(parts[3]); val part = parts[4].toInt(); val total = parts[5].toInt()
                val width = parts[6].toInt(); val height = parts[7].toInt()
                require(total in 1..40 && part in 0 until total)
                require(width in 100..1500 && height in 100..3000)
                val bytes = Base64.getUrlDecoder().decode(parts[8])
                require(bytes.size in 1..2300)
                Packet(relation, session, frame, part, total, width, height, bytes)
            }
            TAP -> Packet(relation, session, x = parts[3].toInt().also {
                require(it in 0..10000)
            }, y = parts[4].toInt().also { require(it in 0..10000) })
            SWIPE -> {
                val coordinates = parts.subList(3, 7).map { it.toInt().also { n ->
                    require(n in 0..10000)
                } }
                val duration = parts[7].toInt().also { require(it in 120..1000) }
                Packet(relation, session, x = coordinates[0], y = coordinates[1],
                    x2 = coordinates[2], y2 = coordinates[3], durationMs = duration)
            }
            ACK -> Packet(relation, session, respondsTo = uuid(parts[3]))
            else -> Packet(relation, session)
        }
    }

    private fun uuid(value: String): String {
        require(value.length == 36 && UUID.fromString(value).toString() == value)
        return value
    }
}
