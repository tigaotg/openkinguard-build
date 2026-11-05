package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.MessageEnvelope
import java.util.Base64
import java.util.UUID

/**
 * Strict, signed, consent-scoped WebRTC signaling and remote gestures.
 * NO video frames, JPEG fragments, raw H.264 or captured pixels are serialized here.
 */
object RemoteAssistStreamWire {
    const val RTC = "OKG_REMOTE_RTC_SIGNAL_V1"
    const val TAP = "OKG_REMOTE_TAP_V1"
    const val SWIPE = "OKG_REMOTE_SWIPE_V1"
    const val BACK = "OKG_REMOTE_BACK_V1"
    const val HOME = "OKG_REMOTE_HOME_V1"
    const val RECENTS = "OKG_REMOTE_RECENTS_V1"
    const val DIAGNOSTIC = "OKG_REMOTE_DIAG_V1"
    const val ACK = "OKG_REMOTE_STREAM_ACK_V1"
    val requestKinds = setOf(RTC, TAP, SWIPE, BACK, HOME, RECENTS, DIAGNOSTIC)
    val kinds = requestKinds + ACK

    /** Private WSS fallback is only for small signed control/signaling packets.
     * WebRTC media, screen frames, and ACKs are not outbound requests here.
     */
    fun canUsePrivateRelay(kind: String): Boolean = kind in requestKinds

    data class Packet(
        val relationId: String,
        val sessionId: String,
        val x: Int = 0,
        val y: Int = 0,
        val x2: Int = 0,
        val y2: Int = 0,
        val durationMs: Int = 0,
        val respondsTo: String? = null,
        val diagnosticText: String = "",
        val rtcType: String = "",
        val rtcId: String = "",
        val rtcPart: Int = 0,
        val rtcTotal: Int = 0,
        val rtcBytes: ByteArray = byteArrayOf(),
    )

    /** Chunks are signed individually and reassembled under the approved session. */
    fun rtc(relationId: String, sessionId: String, type: String, id: String,
            part: Int, total: Int, payload: ByteArray): ByteArray {
        require(type in setOf("offer", "answer", "ice"))
        require(total in 1..40 && part in 0 until total && payload.size in 1..950)
        return ("R|${uuid(relationId)}|${uuid(sessionId)}|$type|${uuid(id)}|$part|$total|" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(payload))
            .toByteArray(Charsets.US_ASCII)
    }

    fun tap(relationId: String, sessionId: String, x: Int, y: Int): ByteArray {
        require(x in 0..10000 && y in 0..10000)
        return "T|${uuid(relationId)}|${uuid(sessionId)}|$x|$y"
            .toByteArray(Charsets.US_ASCII)
    }

    fun action(relationId: String, sessionId: String, kind: String): ByteArray {
        require(kind == BACK || kind == HOME || kind == RECENTS)
        val prefix = when (kind) { BACK -> "B"; HOME -> "H"; else -> "M" }
        return "$prefix|${uuid(relationId)}|${uuid(sessionId)}"
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

    /** Aggregate performance telemetry; does not contain image, secrets or user messages. */
    fun diagnostic(relationId: String, sessionId: String, content: String): ByteArray {
        val utf8 = content.toByteArray(Charsets.UTF_8)
        require(utf8.size in 1..1100)
        val data = Base64.getUrlEncoder().withoutPadding().encodeToString(utf8)
        return "D|${uuid(relationId)}|${uuid(sessionId)}|$data"
            .toByteArray(Charsets.US_ASCII)
    }

    fun decode(message: MessageEnvelope): Packet {
        require(message.payloadKind in kinds) { "Unknown remote assist packet" }
        require(message.payload.size in 75..4300 &&
            message.payload.all { it.toInt() in 32..126 }) { "Invalid remote assist packet" }
        val parts = message.payload.toString(Charsets.US_ASCII).split('|')
        val expected = when (message.payloadKind) {
            RTC -> "R"; TAP -> "T"; SWIPE -> "W"; BACK -> "B"
            HOME -> "H"; RECENTS -> "M"; DIAGNOSTIC -> "D"; else -> "A"
        }
        require(parts.firstOrNull() == expected) { "Packet kind mismatch" }
        require(parts.size == when (message.payloadKind) {
            RTC -> 8; TAP -> 5; SWIPE -> 8; BACK, HOME, RECENTS -> 3; else -> 4
        }) { "Invalid packet fields" }
        val relation = uuid(parts[1])
        val session = uuid(parts[2])
        require(message.eventId.value == session) { "Session mismatch" }
        return when (message.payloadKind) {
            RTC -> {
                val type = parts[3].also { require(it in setOf("offer", "answer", "ice")) }
                val id = uuid(parts[4])
                val part = parts[5].toInt()
                val total = parts[6].toInt()
                require(total in 1..40 && part in 0 until total)
                val bytes = Base64.getUrlDecoder().decode(parts[7])
                require(bytes.size in 1..950)
                Packet(relation, session, rtcType = type, rtcId = id,
                    rtcPart = part, rtcTotal = total, rtcBytes = bytes)
            }
            TAP -> Packet(relation, session, x = parts[3].toInt().also {
                require(it in 0..10000)
            }, y = parts[4].toInt().also { require(it in 0..10000) })
            SWIPE -> {
                val xy = parts.subList(3, 7).map {
                    it.toInt().also { n -> require(n in 0..10000) }
                }
                val duration = parts[7].toInt().also { require(it in 120..1000) }
                Packet(relation, session, x = xy[0], y = xy[1],
                    x2 = xy[2], y2 = xy[3], durationMs = duration)
            }
            ACK -> Packet(relation, session, respondsTo = uuid(parts[3]))
            DIAGNOSTIC -> {
                val bytes = Base64.getUrlDecoder().decode(parts[3])
                require(bytes.size in 1..1100)
                val text = Charsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                Packet(relation, session, diagnosticText = text)
            }
            else -> Packet(relation, session)
        }
    }

    private fun uuid(value: String): String {
        require(value.length == 36 && UUID.fromString(value).toString() == value)
        return value
    }
}
