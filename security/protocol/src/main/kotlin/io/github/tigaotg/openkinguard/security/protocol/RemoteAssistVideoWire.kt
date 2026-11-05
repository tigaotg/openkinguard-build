package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.MessageEnvelope
import java.util.UUID

/** Each signed header authenticates the exact SHA-256 checked binary access unit. */
object RemoteAssistVideoWire {
    const val OPEN = "OKG_ASSIST_VIDEO_OPEN_V2"
    const val UNIT = "OKG_ASSIST_VIDEO_UNIT_V2"
    const val ACK = "OKG_ASSIST_VIDEO_ACK_V2"
    const val MAX_UNIT_BYTES = 288_000
    val requestKinds = setOf(OPEN, UNIT)

    data class Packet(val relationId: String, val sessionId: String,
        val respondsTo: String = "", val unitId: Long = 0,
        val width: Int = 0, val height: Int = 0,
        val presentationUs: Long = 0, val flags: Int = 0,
        val length: Int = 0, val sha256: String = "")

    fun open(relationId: String, sessionId: String): ByteArray =
        "O|${uuid(relationId)}|${uuid(sessionId)}".toByteArray(Charsets.US_ASCII)

    fun ack(relationId: String, sessionId: String, respondsTo: String): ByteArray =
        "A|${uuid(relationId)}|${uuid(sessionId)}|${uuid(respondsTo)}"
            .toByteArray(Charsets.US_ASCII)

    fun unit(relationId: String, sessionId: String, unitId: Long,
             width: Int, height: Int, presentationUs: Long, flags: Int,
             length: Int, sha256: String): ByteArray {
        require(unitId > 0)
        require(width in 100..1920 && height in 100..1920)
        require(presentationUs >= 0 && flags in 0..31 && length in 1..MAX_UNIT_BYTES)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        return "U|${uuid(relationId)}|${uuid(sessionId)}|$unitId|$width|".plus(
            "$height|$presentationUs|$flags|$length|$sha256").toByteArray(Charsets.US_ASCII)
    }

    fun decode(message: MessageEnvelope): Packet {
        require(message.payloadKind in requestKinds + ACK)
        require(message.payload.size in 70..512 &&
            message.payload.all { it.toInt() in 32..126 })
        val tokens = message.payload.toString(Charsets.US_ASCII).split('|')
        val type = when (message.payloadKind) { OPEN -> "O"; UNIT -> "U"; else -> "A" }
        require(tokens[0] == type && tokens.size == when (type) {
            "O" -> 3; "A" -> 4; else -> 10
        })
        val relationId = uuid(tokens[1]); val sessionId = uuid(tokens[2])
        require(message.eventId.value == sessionId)
        return when (type) {
            "O" -> Packet(relationId, sessionId)
            "A" -> Packet(relationId, sessionId, respondsTo = uuid(tokens[3]))
            else -> {
                val id = tokens[3].toLong().also { require(it > 0) }
                val width = tokens[4].toInt(); val height = tokens[5].toInt()
                require(width in 100..1920 && height in 100..1920)
                val pts = tokens[6].toLong().also { require(it >= 0) }
                val flags = tokens[7].toInt().also { require(it in 0..31) }
                val length = tokens[8].toInt().also { require(it in 1..MAX_UNIT_BYTES) }
                val hash = tokens[9].also { require(it.matches(Regex("[0-9a-f]{64}"))) }
                Packet(relationId, sessionId, unitId = id, width = width, height = height,
                    presentationUs = pts, flags = flags, length = length, sha256 = hash)
            }
        }
    }

    private fun uuid(value: String): String {
        require(value.length == 36 && UUID.fromString(value).toString() == value)
        return value
    }
}
