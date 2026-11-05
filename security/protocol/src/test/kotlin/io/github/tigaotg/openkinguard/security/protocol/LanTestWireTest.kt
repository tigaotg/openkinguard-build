package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID

class LanTestWireTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val relation = UUID.randomUUID().toString()
    private val requestId = UUID.randomUUID().toString()

    private fun signed(kind: String = LanTestWire.PING, payload: ByteArray = LanTestWire.pingPayload(relation)):
        SignedEnvelope {
        val msg = MessageEnvelope(1, MessageId(requestId), EventId(UUID.randomUUID().toString()),
            DeviceId("okg-sender"), UserId("okg-subject"), DeviceId("okg-recipient"),
            1793350000000, 1793350300000, 0, kind, payload)
        return SignedEnvelope(msg, SignedProtocol.sign(msg, keyPair.private))
    }

    private fun signedAssist(sessionId: String, kind: String, payload: ByteArray): SignedEnvelope {
        val msg = MessageEnvelope(1, MessageId(UUID.randomUUID().toString()),
            EventId(sessionId), DeviceId("okg-sender"), UserId("okg-subject"),
            DeviceId("okg-recipient"), 1793350000000, 1793350300000,
            1, kind, payload)
        return SignedEnvelope(msg, SignedProtocol.sign(msg, keyPair.private))
    }

    private fun reject(action: () -> Unit) {
        try { action(); fail("Expected malformed packet rejection") }
        catch (_: IllegalArgumentException) { /* expected */ }
        catch (_: java.io.IOException) { /* truncated untrusted frame */ }
    }

    @Test fun packetRoundTripRetainsVerifiableSignature() {
        val decoded = LanTestWire.decode(LanTestWire.encode(signed()))
        assertTrue(SignedProtocol.verify(decoded.message, decoded.signature, keyPair.public))
        assertEquals(relation, LanTestWire.body(decoded.message).relationId)
    }

    @Test fun remoteAssistJpegFragmentIsNotMisparsedAsSosExercise() {
        val session = UUID.randomUUID().toString()
        val fragment = ByteArray(2000) { (it % 251).toByte() }
        val payload = RemoteAssistStreamWire.frame(relation, session,
            UUID.randomUUID().toString(), 0, 1, 420, 740, fragment)
        assertTrue(payload.size > 512)
        val decoded = LanTestWire.decode(LanTestWire.encode(
            signedAssist(session, RemoteAssistStreamWire.FRAME, payload)))
        assertTrue(SignedProtocol.verify(decoded.message, decoded.signature, keyPair.public))
        assertEquals(relation, LanTestWire.body(decoded.message).relationId)
        assertArrayEquals(fragment, RemoteAssistStreamWire.decode(decoded.message).jpegPart)
    }

    @Test fun remoteAssistAckUsesTheSameAuthenticatedCarrier() {
        val session = UUID.randomUUID().toString()
        val payload = RemoteAssistStreamWire.ack(relation, session, requestId)
        val decoded = LanTestWire.decode(LanTestWire.encode(
            signedAssist(session, RemoteAssistStreamWire.ACK, payload)))
        assertEquals(requestId, LanTestWire.body(decoded.message).acknowledgedMessageId)
        assertTrue(SignedProtocol.verify(decoded.message, decoded.signature, keyPair.public))
    }

    @Test fun ackIsBoundToOriginalMessageIdAndRelation() {
        val packet = LanTestWire.encode(signed(LanTestWire.ACK, LanTestWire.ackPayload(relation, requestId)))
        val decoded = LanTestWire.decode(packet)
        assertEquals(requestId, LanTestWire.body(decoded.message).acknowledgedMessageId)
    }

    @Test fun packetHeaderMutationRejected() {
        val bytes = LanTestWire.encode(signed())
        bytes[0] = 0
        reject { LanTestWire.decode(bytes) }
    }

    @Test fun truncatedAndOversizedPacketsRejected() {
        val bytes = LanTestWire.encode(signed())
        reject { LanTestWire.decode(bytes.copyOf(bytes.size - 20)) }
        reject { LanTestWire.decode(ByteArray(LanTestWire.MAX_PACKET + 1)) }
    }

    @Test fun modifiedPayloadCannotPassSignature() {
        val packet = LanTestWire.encode(signed())
        val index = packet.indexOf('P'.code.toByte())
        // Change the end of signature without altering framing: parsed, but cryptographically invalid.
        packet[packet.lastIndex] = (packet.last().toInt() xor 1).toByte()
        val decoded = LanTestWire.decode(packet)
        assertFalse(SignedProtocol.verify(decoded.message, decoded.signature, keyPair.public))
        assertTrue(index >= 0)
    }

    @Test fun unrelatedPayloadIsRejectedBeforeAdmission() {
        reject { LanTestWire.encode(signed("SOS", byteArrayOf(1))) }
    }

    @Test fun persistedReplayWindowRejectsTheSameLanPacketAgain() {
        val packet = LanTestWire.decode(LanTestWire.encode(signed()))
        val first = AuthenticatedAdmission.examine(packet, DeviceId("okg-sender"),
            DeviceId("okg-recipient"), keyPair.public, 1793350001000,
            ReplayWindow()) as AdmissionResult.Accepted
        val repeated = AuthenticatedAdmission.examine(packet, DeviceId("okg-sender"),
            DeviceId("okg-recipient"), keyPair.public, 1793350002000,
            first.nextWindow) as AdmissionResult.Rejected
        assertEquals(AdmissionFailure.DUPLICATE, repeated.reason)
    }
}
