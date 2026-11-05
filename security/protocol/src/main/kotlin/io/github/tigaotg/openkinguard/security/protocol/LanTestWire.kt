package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** Test-only, bounded LAN packet format. Does not serialize real SOS or private data. */
object LanTestWire {
    const val PING = "OKG_LAN_TEST_PING_V1"
    const val ACK = "OKG_LAN_TEST_ACK_V1"
    const val PORT = 38671
    const val MAX_PACKET = 8192
    private const val MAGIC = 0x4f4b4c31 // OKL1

    data class Body(val relationId: String, val acknowledgedMessageId: String? = null)

    fun pingPayload(relationId: String): ByteArray =
        "P|${requireUuid(relationId)}".toByteArray(Charsets.UTF_8)

    fun ackPayload(relationId: String, messageId: String): ByteArray =
        "A|${requireUuid(relationId)}|${requireUuid(messageId)}".toByteArray(Charsets.UTF_8)

    fun body(message: MessageEnvelope): Body {
        val text = message.payload.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(message.payload)) { "Non-UTF8 payload" }
        val parts = text.split('|')
        return when (message.payloadKind) {
            PING -> {
                require(parts.size == 2 && parts[0] == "P") { "Invalid test ping" }
                Body(requireUuid(parts[1]))
            }
            ACK -> {
                require(parts.size == 3 && parts[0] == "A") { "Invalid test receipt" }
                Body(requireUuid(parts[1]), requireUuid(parts[2]))
            }
            in S3LocationWire.kinds -> {
                val location = S3LocationWire.body(message)
                Body(location.relationId, location.respondingTo)
            }
            in RemoteAssistWire.kinds -> {
                val assist = RemoteAssistWire.body(message)
                Body(assist.relationId, assist.respondingTo)
            }
            in RemoteAssistStreamWire.kinds -> {
                // The existing authenticated LAN frame carrier also handles
                // voluntary screen fragments and their signed acknowledgements.
                val assist = RemoteAssistStreamWire.decode(message)
                Body(assist.relationId, assist.respondsTo)
            }
            else -> {
                // The same bounded, signed frame carrier also transports S1 exercise cases.
                val exercise = S1CaseWire.body(message)
                Body(exercise.relationId, exercise.respondingTo)
            }
        }
    }

    fun encode(signed: SignedEnvelope): ByteArray {
        val msg = signed.message
        body(msg)
        SignedProtocol.bytesToSign(msg) // bounds + supported protocol checked here
        require(signed.signature.size in 64..80) { "Invalid signature size" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(msg.protocolVersion)
            out.writeUTF(msg.messageId.value)
            out.writeUTF(msg.eventId.value)
            out.writeUTF(msg.senderDeviceId.value)
            out.writeUTF(msg.subjectId.value)
            out.writeUTF(msg.recipientDeviceId.value)
            out.writeLong(msg.createdEpochMillis)
            out.writeLong(msg.expiresEpochMillis)
            out.writeLong(msg.sequence)
            out.writeUTF(msg.payloadKind)
            out.writeInt(msg.payload.size)
            out.write(msg.payload)
            out.writeInt(signed.signature.size)
            out.write(signed.signature)
        }
        return bytes.toByteArray().also { require(it.size <= MAX_PACKET) }
    }

    fun decode(packet: ByteArray): SignedEnvelope {
        require(packet.size in 100..MAX_PACKET) { "Invalid LAN packet size" }
        return DataInputStream(ByteArrayInputStream(packet)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid LAN packet header" }
            val version = input.readInt()
            val messageId = MessageId(input.readUTF())
            val eventId = EventId(input.readUTF())
            val sender = DeviceId(input.readUTF())
            val subject = UserId(input.readUTF())
            val recipient = DeviceId(input.readUTF())
            val created = input.readLong()
            val expires = input.readLong()
            val sequence = input.readLong()
            val kind = input.readUTF()
            val length = input.readInt()
            // Keep the original 512-byte bound for SOS/location/control.
            // A screen frame may contain a separately validated JPEG fragment.
            val maxLength = if (kind in RemoteAssistStreamWire.kinds) 4300 else 512
            require(length in 1..maxLength && length <= input.available() - 4) {
                "Invalid payload length"
            }
            val payload = ByteArray(length).also(input::readFully)
            val sigLength = input.readInt()
            require(sigLength in 64..80 && sigLength == input.available()) { "Invalid signature length" }
            val signature = ByteArray(sigLength).also(input::readFully)
            val message = MessageEnvelope(version, messageId, eventId, sender, subject,
                recipient, created, expires, sequence, kind, payload)
            body(message)
            SignedProtocol.bytesToSign(message)
            SignedEnvelope(message, signature)
        }
    }

    private fun requireUuid(text: String): String {
        require(text.length == 36 && UUID.fromString(text).toString() == text) { "Invalid relation/message ID" }
        return text
    }
}
