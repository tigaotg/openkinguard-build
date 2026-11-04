package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.security.KeyPairGenerator

class S1CaseWireTest {
    private val relation = UUID.randomUUID().toString()
    private val event = UUID.randomUUID().toString()
    private val messageId = UUID.randomUUID().toString()

    private fun msg(kind: String, payload: ByteArray, id: String = event) = MessageEnvelope(
        1, MessageId(messageId), EventId(id), DeviceId("sender"), UserId("subject"),
        DeviceId("recipient"), 1L, 5000L, 0L, kind, payload)

    @Test fun `exercise requests have a tiny strict payload`() {
        for (kind in S1CaseWire.requestKinds) {
            val message = msg(kind, S1CaseWire.request(relation, event))
            val body = S1CaseWire.body(message)
            assertEquals(relation, body.relationId)
            assertEquals(event, body.eventId)
            assertNull(body.respondingTo)
            assertTrue(message.payload.size < 90)
        }
    }

    @Test fun `responses are correlated with exact message`() {
        for (kind in S1CaseWire.responseKinds) {
            val payload = if (kind == S1CaseWire.PROFILE_REPLY)
                S1CaseWire.profileResponse(relation, event, messageId, "红米手机")
            else S1CaseWire.response(relation, event, messageId)
            val body = S1CaseWire.body(msg(kind, payload))
            assertEquals(messageId, body.respondingTo)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `different event header rejected`() {
        S1CaseWire.body(msg(S1CaseWire.SOS, S1CaseWire.request(relation, event),
            UUID.randomUUID().toString()))
    }

    @Test fun `takeover first claimant wins and same claimant is idempotent`() {
        assertTrue(S1CaseWire.claimDecision(null, "A"))
        assertTrue(S1CaseWire.claimDecision("A", "A"))
        assertFalse(S1CaseWire.claimDecision("A", "B"))
    }

    @Test fun `response kinds enforce protocol progression`() {
        assertEquals(setOf(S1CaseWire.RECEIPT), S1CaseWire.expectedResponse(S1CaseWire.SOS))
        assertEquals(setOf(S1CaseWire.ACCEPT, S1CaseWire.REJECT),
            S1CaseWire.expectedResponse(S1CaseWire.CLAIM))
        assertTrue(S1CaseWire.expectedResponse(S1CaseWire.RECEIPT).isEmpty())
        assertEquals(setOf(S1CaseWire.CHECKIN_RECEIPT),
            S1CaseWire.expectedResponse(S1CaseWire.CHECKIN))
        assertEquals(setOf(S1CaseWire.PROFILE_REPLY),
            S1CaseWire.expectedResponse(S1CaseWire.PROFILE))
    }

    @Test fun `signed full exercise frame survives bounded LAN carrier`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val message = msg(S1CaseWire.SOS, S1CaseWire.request(relation, event))
        val signature = SignedProtocol.sign(message, pair.private)
        val decoded = LanTestWire.decode(LanTestWire.encode(SignedEnvelope(message, signature)))
        assertEquals(event, S1CaseWire.body(decoded.message).eventId)
        assertTrue(SignedProtocol.verify(decoded.message, decoded.signature, pair.public))
    }

    @Test fun `signed checkin and correlated receipt survive LAN carrier`() {
        val sender = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val receiver = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val checkin = msg(S1CaseWire.CHECKIN, S1CaseWire.request(relation, event))
        val sent = LanTestWire.decode(LanTestWire.encode(SignedEnvelope(
            checkin, SignedProtocol.sign(checkin, sender.private))))
        assertTrue(SignedProtocol.verify(sent.message, sent.signature, sender.public))
        assertEquals(relation, S1CaseWire.body(sent.message).relationId)

        val receiptId = UUID.randomUUID().toString()
        val receipt = MessageEnvelope(1, MessageId(receiptId), EventId(event),
            DeviceId("receiver"), UserId("subject"), DeviceId("sender"), 2L,
            5000L, 0L, S1CaseWire.CHECKIN_RECEIPT,
            S1CaseWire.response(relation, event, sent.message.messageId.value))
        val returned = LanTestWire.decode(LanTestWire.encode(SignedEnvelope(
            receipt, SignedProtocol.sign(receipt, receiver.private))))
        assertTrue(SignedProtocol.verify(returned.message, returned.signature, receiver.public))
        assertEquals(sent.message.messageId.value,
            S1CaseWire.body(returned.message).respondingTo)
        assertEquals(setOf(returned.message.payloadKind),
            S1CaseWire.expectedResponse(sent.message.payloadKind))
    }

    @Test fun `nickname is transported in signed correlated profile reply`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val name = "荣耀7X · 家人"
        val message = msg(S1CaseWire.PROFILE_REPLY,
            S1CaseWire.profileResponse(relation, event, messageId, name))
        val sent = LanTestWire.decode(LanTestWire.encode(SignedEnvelope(
            message, SignedProtocol.sign(message, pair.private))))
        assertTrue(SignedProtocol.verify(sent.message, sent.signature, pair.public))
        val parsed = S1CaseWire.body(sent.message)
        assertEquals(messageId, parsed.respondingTo)
        assertEquals(name, parsed.profileNickname)
        assertEquals(relation, parsed.relationId)
        assertEquals(event, parsed.eventId)
    }

    @Test fun `malformed or control character nicknames are rejected`() {
        assertTrue(runCatching { S1CaseWire.profileResponse(relation, event, messageId,
            "伪装\n名称") }.isFailure)
        assertTrue(runCatching { S1CaseWire.profileResponse(relation, event, messageId,
            "这是一个超过十八个字符的很长很长很长的设备名称") }.isFailure)
        assertTrue(runCatching { S1CaseWire.body(msg(S1CaseWire.PROFILE_REPLY,
            S1CaseWire.response(relation, event, messageId))) }.isFailure)
    }

    @Test fun `signed event creation time survives wire and permits legacy request parsing`() {
        val created = 1_782_900_000_123L
        for (kind in listOf(S1CaseWire.SOS, S1CaseWire.CHECKIN)) {
            val modern = S1CaseWire.body(msg(kind,
                S1CaseWire.request(relation, event, created)))
            assertEquals(created, modern.eventCreatedAtMillis)
            assertEquals(event, modern.eventId)
            assertNull(S1CaseWire.body(msg(kind,
                S1CaseWire.request(relation, event))).eventCreatedAtMillis)
        }
    }

    @Test fun `event time cannot be smuggled into a claim or malformed`() {
        val forbidden = S1CaseWire.request(relation, event, 100L)
        assertTrue(runCatching { S1CaseWire.body(msg(S1CaseWire.CLAIM, forbidden)) }.isFailure)
        for (badTime in listOf("0", "-1", "001", "999999999999999999999999", "nan")) {
            val invalid = "R|$relation|$event|$badTime".toByteArray(Charsets.UTF_8)
            assertTrue(runCatching { S1CaseWire.body(msg(S1CaseWire.SOS, invalid)) }.isFailure)
        }
    }

    @Test fun `signed events carry source nickname at point of delivery`() {
        for (kind in listOf(S1CaseWire.SOS, S1CaseWire.CHECKIN)) {
            val body = S1CaseWire.body(msg(kind,
                S1CaseWire.request(relation, event, 1_783_000_000_000L, "张奶奶的手机")))
            assertEquals("张奶奶的手机", body.senderNickname)
            assertEquals(1_783_000_000_000L, body.eventCreatedAtMillis)
        }
        for (kind in listOf(S1CaseWire.CLAIM, S1CaseWire.CLOSE)) {
            val body = S1CaseWire.body(msg(kind,
                S1CaseWire.request(relation, event, senderNickname = "家人助手")))
            assertEquals("家人助手", body.senderNickname)
            assertNull(body.eventCreatedAtMillis)
        }
    }

    @Test fun `nicknames are strict and cannot be confused with source timestamps`() {
        val incorrect = listOf("R|$relation|$event|N:YWJj",
            "R|$relation|$event|123|N:", "R|$relation|$event|123|N:###",
            "R|$relation|$event|123|N:YWJj|extra")
        incorrect.forEach { payload ->
            assertTrue(runCatching { S1CaseWire.body(msg(S1CaseWire.SOS,
                payload.toByteArray(Charsets.UTF_8))) }.isFailure)
        }
        assertTrue(runCatching { S1CaseWire.request(relation, event,
            senderNickname = "恶意\n名称") }.isFailure)
    }
}
