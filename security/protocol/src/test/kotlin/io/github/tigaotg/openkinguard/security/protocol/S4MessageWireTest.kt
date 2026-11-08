package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID

class S4MessageWireTest {
    private val relation = UUID.randomUUID().toString()
    private val event = UUID.randomUUID().toString()
    private val sealed = S4MessageWire.SealedPayload(
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(91) { 1 }),
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(12) { 2 }),
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(80) { 3 }),
    )

    @Test fun wrapperRoundTripsOnlyBoundedEncryptedMaterial() {
        val wire = S4MessageWire.encode(relation, sealed)
        val decoded = S4MessageWire.decode(wire)
        assertEquals(relation, decoded.relationId)
        assertEquals(sealed, decoded.sealed)
        assertTrue(wire.size <= 512)
    }

    @Test fun associatedDataBindsRelationshipAndSignedHeader() {
        val message = MessageEnvelope(1, MessageId(UUID.randomUUID().toString()),
            EventId(event), DeviceId("sender"), UserId("subject"), DeviceId("recipient"),
            100L, 200L, 3L, S1CaseWire.SOS, byteArrayOf(1))
        val aad = S4MessageWire.associatedData(relation, message)
        assertEquals(32, aad.size)
        assertFalse(aad.contentEquals(S4MessageWire.associatedData(UUID.randomUUID().toString(), message)))
        assertFalse(aad.contentEquals(S4MessageWire.associatedData(relation,
            message.copy(expiresEpochMillis = 201L))))
        assertFalse(aad.contentEquals(S4MessageWire.associatedData(relation,
            message.copy(recipientDeviceId = DeviceId("other-recipient")))))
    }

    @Test fun stableMessageIdIsDeterministicAndDirectionSpecific() {
        val first = S4MessageWire.stableMessageId(event, relation, "sender", "recipient", S1CaseWire.SOS)
        assertEquals(first, S4MessageWire.stableMessageId(event, relation, "sender", "recipient", S1CaseWire.SOS))
        assertNotEquals(first, S4MessageWire.stableMessageId(event, relation, "recipient", "sender", S1CaseWire.SOS))
        assertNotEquals(first, S4MessageWire.stableMessageId(event, relation, "sender", "recipient", S1CaseWire.CHECKIN))
    }

    @Test fun malformedVersionAndOversizedFramesFailClosed() {
        assertTrue(runCatching { S4MessageWire.decode("OKG4|0|bad".toByteArray()) }.isFailure)
        assertTrue(runCatching { S4MessageWire.decode(ByteArray(513)) }.isFailure)
        assertTrue(runCatching {
            S4MessageWire.encode(relation, sealed.copy(ciphertext =
                Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(300) { 7 })))
        }.isFailure)
    }
}
