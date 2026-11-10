package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.security.KeyPairGenerator
import java.util.Base64

class S1CaseWireTest {
    private val relation = UUID.randomUUID().toString()
    private val event = UUID.randomUUID().toString()
    private val messageId = UUID.randomUUID().toString()
    private val sealed = S4MessageWire.SealedPayload(
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(91) { 1 }),
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(12) { 2 }),
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(80) { 3 }),
    )
    private val card = listOf("1", "a".repeat(64), "b".repeat(120),
        "1783000000000", "1783086400000", "c".repeat(88)).joinToString(".")

    private fun msg(kind: String, payload: ByteArray, id: String = event) = MessageEnvelope(
        1, MessageId(messageId), EventId(id), DeviceId("sender"), UserId("subject"),
        DeviceId("recipient"), 1L, 5000L, 0L, kind, payload)

    private fun wire(kind: String, plaintext: ByteArray, id: String = event): MessageEnvelope =
        msg(kind, if (kind in S1CaseWire.encryptedKinds)
            S4MessageWire.encode(relation, sealed) else plaintext, id)

    private fun decoded(kind: String, plaintext: ByteArray, id: String = event): S1CaseWire.Body {
        val message = wire(kind, plaintext, id)
        return if (kind in S1CaseWire.encryptedKinds)
            S1CaseWire.bodyDecrypted(message, plaintext) else S1CaseWire.body(message)
    }

    @Test fun encryptedRequestsExposeOnlyRelationRouteUntilDecrypted() {
        for (kind in S1CaseWire.requestKinds) {
            val plaintext = S1CaseWire.request(relation, event)
            val message = wire(kind, plaintext)
            val routed = S1CaseWire.body(message)
            assertEquals(relation, routed.relationId)
            assertEquals(event, routed.eventId)
            if (kind in S1CaseWire.encryptedKinds) {
                assertNull(routed.respondingTo)
                assertEquals(event, S1CaseWire.bodyDecrypted(message, plaintext).eventId)
                assertTrue(message.payload.size < 512)
            }
        }
    }

    @Test fun encryptedResponsesAreCorrelatedAfterDecryption() {
        for (kind in S1CaseWire.responseKinds) {
            val plaintext = if (kind == S1CaseWire.PROFILE_REPLY)
                S1CaseWire.profileResponse(relation, event, messageId, "红米手机")
            else S1CaseWire.response(relation, event, messageId)
            val body = decoded(kind, plaintext)
            assertEquals(messageId, body.respondingTo)
            assertEquals(relation, body.relationId)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun differentEventHeaderRejectedAfterDecryption() {
        val message = wire(S1CaseWire.SOS, S1CaseWire.request(relation, event),
            UUID.randomUUID().toString())
        S1CaseWire.bodyDecrypted(message, S1CaseWire.request(relation, event))
    }

    @Test fun takeoverFirstClaimantWinsAndSameClaimantIsIdempotent() {
        assertTrue(S1CaseWire.claimDecision(null, "A"))
        assertTrue(S1CaseWire.claimDecision("A", "A"))
        assertFalse(S1CaseWire.claimDecision("A", "B"))
    }

    @Test fun responseKindsEnforceProtocolProgression() {
        assertEquals(setOf(S1CaseWire.RECEIPT), S1CaseWire.expectedResponse(S1CaseWire.SOS))
        assertEquals(setOf(S1CaseWire.ACCEPT, S1CaseWire.REJECT),
            S1CaseWire.expectedResponse(S1CaseWire.CLAIM))
        assertTrue(S1CaseWire.expectedResponse(S1CaseWire.RECEIPT).isEmpty())
        assertEquals(setOf(S1CaseWire.CHECKIN_RECEIPT),
            S1CaseWire.expectedResponse(S1CaseWire.CHECKIN))
        assertEquals(setOf(S1CaseWire.CHECKIN_CONFIRM_RECEIPT),
            S1CaseWire.expectedResponse(S1CaseWire.CHECKIN_CONFIRM))
        assertEquals(setOf(S1CaseWire.PROFILE_REPLY),
            S1CaseWire.expectedResponse(S1CaseWire.PROFILE))
    }

    @Test fun signedEncryptedExerciseFrameSurvivesBoundedLanCarrier() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val plain = S1CaseWire.request(relation, event, 1_783_000_000_000L, "家人手机")
        val message = wire(S1CaseWire.SOS, plain)
        val signature = SignedProtocol.sign(message, pair.private)
        val decoded = LanTestWire.decode(LanTestWire.encode(SignedEnvelope(message, signature)))
        assertEquals(relation, S1CaseWire.body(decoded.message).relationId)
        assertEquals(event, S1CaseWire.bodyDecrypted(decoded.message, plain).eventId)
        assertTrue(SignedProtocol.verify(decoded.message, decoded.signature, pair.public))
    }

    @Test fun signedCheckinAndCorrelatedReceiptSurviveLanCarrier() {
        val sender = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val receiver = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val checkinPlain = S1CaseWire.request(relation, event)
        val checkin = wire(S1CaseWire.CHECKIN, checkinPlain)
        val sent = LanTestWire.decode(LanTestWire.encode(SignedEnvelope(
            checkin, SignedProtocol.sign(checkin, sender.private))))
        assertTrue(SignedProtocol.verify(sent.message, sent.signature, sender.public))
        assertEquals(relation, S1CaseWire.body(sent.message).relationId)
        assertEquals(event, S1CaseWire.bodyDecrypted(sent.message, checkinPlain).eventId)

        val receiptPlain = S1CaseWire.response(relation, event, sent.message.messageId.value)
        val receipt = wire(S1CaseWire.CHECKIN_RECEIPT, receiptPlain)
        val returned = LanTestWire.decode(LanTestWire.encode(SignedEnvelope(
            receipt, SignedProtocol.sign(receipt, receiver.private))))
        assertTrue(SignedProtocol.verify(returned.message, returned.signature, receiver.public))
        assertEquals(sent.message.messageId.value,
            S1CaseWire.bodyDecrypted(returned.message, receiptPlain).respondingTo)
        assertEquals(setOf(returned.message.payloadKind),
            S1CaseWire.expectedResponse(sent.message.payloadKind))
    }

    @Test fun checkinFamilyConfirmationIsEncryptedAndCorrelated() {
        val plain = S1CaseWire.request(relation, event, senderNickname = "家属手机")
        val message = wire(S1CaseWire.CHECKIN_CONFIRM, plain)
        assertEquals(relation, S1CaseWire.body(message).relationId)
        val parsed = S1CaseWire.bodyDecrypted(message, plain)
        assertEquals(event, parsed.eventId)
        assertEquals("家属手机", parsed.senderNickname)
        val receiptPlain = S1CaseWire.response(relation, event, messageId)
        val receipt = decoded(S1CaseWire.CHECKIN_CONFIRM_RECEIPT, receiptPlain)
        assertEquals(messageId, receipt.respondingTo)
        assertEquals(setOf(S1CaseWire.CHECKIN_CONFIRM_RECEIPT),
            S1CaseWire.expectedResponse(S1CaseWire.CHECKIN_CONFIRM))
    }

    @Test fun profileExchangeCarriesSignedEncryptionCard() {
        val request = S1CaseWire.body(msg(S1CaseWire.PROFILE,
            S1CaseWire.request(relation, event, encryptionCard = card)))
        assertEquals(card, request.encryptionCard)
        val response = S1CaseWire.body(msg(S1CaseWire.PROFILE_REPLY,
            S1CaseWire.profileResponse(relation, event, messageId, "红米手机", card)))
        assertEquals(card, response.encryptionCard)
        assertEquals("红米手机", response.profileNickname)
    }

    @Test fun nicknameIsTransportedInSignedCorrelatedProfileReply() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val name = "荣耀7X · 家人"
        val message = msg(S1CaseWire.PROFILE_REPLY,
            S1CaseWire.profileResponse(relation, event, messageId, name, card))
        val sent = LanTestWire.decode(LanTestWire.encode(SignedEnvelope(
            message, SignedProtocol.sign(message, pair.private))))
        assertTrue(SignedProtocol.verify(sent.message, sent.signature, pair.public))
        val parsed = S1CaseWire.body(sent.message)
        assertEquals(messageId, parsed.respondingTo)
        assertEquals(name, parsed.profileNickname)
        assertEquals(card, parsed.encryptionCard)
        assertEquals(relation, parsed.relationId)
        assertEquals(event, parsed.eventId)
    }

    @Test fun malformedOrControlCharacterNicknamesAndCardsAreRejected() {
        assertTrue(runCatching { S1CaseWire.profileResponse(relation, event, messageId,
            "伪装\n名称") }.isFailure)
        assertTrue(runCatching { S1CaseWire.profileResponse(relation, event, messageId,
            "这是一个超过十八个字符的很长很长很长的设备名称") }.isFailure)
        assertTrue(runCatching { S1CaseWire.body(msg(S1CaseWire.PROFILE_REPLY,
            S1CaseWire.response(relation, event, messageId))) }.isFailure)
        assertTrue(runCatching { S1CaseWire.request(relation, event, encryptionCard = "not-a-card") }.isFailure)
    }

    @Test fun signedEventCreationTimeSurvivesDecryptedWire() {
        val created = 1_782_900_000_123L
        for (kind in listOf(S1CaseWire.SOS, S1CaseWire.CHECKIN)) {
            val body = decoded(kind, S1CaseWire.request(relation, event, created))
            assertEquals(created, body.eventCreatedAtMillis)
            assertEquals(event, body.eventId)
            assertNull(decoded(kind, S1CaseWire.request(relation, event)).eventCreatedAtMillis)
        }
    }

    @Test fun eventTimeCannotBeSmuggledIntoClaimOrMalformed() {
        val forbidden = S1CaseWire.request(relation, event, 100L)
        assertTrue(runCatching { decoded(S1CaseWire.CLAIM, forbidden) }.isFailure)
        for (badTime in listOf("0", "-1", "001", "999999999999999999999999", "nan")) {
            val invalid = "R|$relation|$event|$badTime".toByteArray(Charsets.UTF_8)
            assertTrue(runCatching { decoded(S1CaseWire.SOS, invalid) }.isFailure)
        }
    }

    @Test fun signedEventsCarrySourceNicknameAtPointOfDelivery() {
        for (kind in listOf(S1CaseWire.SOS, S1CaseWire.CHECKIN)) {
            val body = decoded(kind, S1CaseWire.request(relation, event,
                1_783_000_000_000L, "张奶奶的手机"))
            assertEquals("张奶奶的手机", body.senderNickname)
            assertEquals(1_783_000_000_000L, body.eventCreatedAtMillis)
        }
        for (kind in listOf(S1CaseWire.CLAIM, S1CaseWire.CLOSE)) {
            val body = decoded(kind, S1CaseWire.request(relation, event,
                senderNickname = "家人助手"))
            assertEquals("家人助手", body.senderNickname)
            assertNull(body.eventCreatedAtMillis)
        }
    }

    @Test fun nicknamesAreStrictAndCannotBeConfusedWithSourceTimestamps() {
        val incorrect = listOf("R|$relation|$event|N:YWJj",
            "R|$relation|$event|123|N:", "R|$relation|$event|123|N:###",
            "R|$relation|$event|123|N:YWJj|extra")
        incorrect.forEach { payload ->
            assertTrue(runCatching { decoded(S1CaseWire.SOS,
                payload.toByteArray(Charsets.UTF_8)) }.isFailure)
        }
        assertTrue(runCatching { S1CaseWire.request(relation, event,
            senderNickname = "恶意\n名称") }.isFailure)
    }
    @Test fun twoPeerSimulatorAcceptsNicknameOnlySosAndCheckin() {
        // A and B already have the same authorized relation. The sender can
        // transmit a signed event before its originating timestamp is cached.
        val sender = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val receiver = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        for (kind in listOf(S1CaseWire.SOS, S1CaseWire.CHECKIN)) {
            val body = S1CaseWire.request(relation, event, senderNickname = "家人手机")
            val outbound = wire(kind, body)
            val received = LanTestWire.decode(LanTestWire.encode(SignedEnvelope(
                outbound, SignedProtocol.sign(outbound, sender.private))))
            assertTrue(SignedProtocol.verify(received.message, received.signature, sender.public))
            assertEquals(relation, S1CaseWire.body(received.message).relationId)
            val parsed = S1CaseWire.bodyDecrypted(received.message, body)
            assertEquals("家人手机", parsed.senderNickname)
            assertNull(parsed.eventCreatedAtMillis)
            val receiptKind = if (kind == S1CaseWire.SOS)
                S1CaseWire.RECEIPT else S1CaseWire.CHECKIN_RECEIPT
            val receiptPlain = S1CaseWire.response(relation, event, received.message.messageId.value)
            val receiptMessage = wire(receiptKind, receiptPlain)
            val receipt = LanTestWire.decode(LanTestWire.encode(SignedEnvelope(
                receiptMessage, SignedProtocol.sign(receiptMessage, receiver.private))))
            assertTrue(SignedProtocol.verify(receipt.message, receipt.signature, receiver.public))
            assertEquals(received.message.messageId.value,
                S1CaseWire.bodyDecrypted(receipt.message, receiptPlain).respondingTo)
        }
    }

}
