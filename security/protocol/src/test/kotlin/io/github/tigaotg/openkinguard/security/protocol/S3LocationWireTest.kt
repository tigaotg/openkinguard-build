package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.UUID

class S3LocationWireTest {
    private val relation = UUID.randomUUID().toString()
    private val event = UUID.randomUUID().toString()
    private val messageId = UUID.randomUUID().toString()
    private val fix = S3LocationWire.Fix(341_999_999, 1_089_000_001, 1250,
        1_800_000_000_000L, "G")

    private fun msg(kind: String, payload: ByteArray, id: String = event) = MessageEnvelope(
        1, MessageId(messageId), EventId(id), DeviceId("sender"), UserId("subject"),
        DeviceId("recipient"), 1_800_000_000_000L, 1_800_000_180_000L, 1L, kind, payload)

    @Test fun `one shot request includes no coordinates and fixes correlate to its message`() {
        val requester = S3LocationCrypto.ephemeral()
        val request = S3LocationWire.body(msg(S3LocationWire.REQUEST,
            S3LocationWire.request(relation, event,
                S3LocationCrypto.encodePublic(requester.public))))
        assertEquals(relation, request.relationId)
        assertNull(request.encrypted)
        val encrypted = S3LocationCrypto.encrypt(fix, requireNotNull(request.requestPublicKey),
            relation, event, messageId)
        val reply = S3LocationWire.body(msg(S3LocationWire.RESULT,
            S3LocationWire.result(relation, event, messageId, encrypted)))
        assertEquals(messageId, reply.respondingTo)
        assertEquals(encrypted, reply.encrypted)
        val decoded = S3LocationCrypto.decrypt(requester.private,
            requireNotNull(reply.encrypted), relation, event, messageId)
        assertEquals(fix, decoded)
        assertEquals(34.1999999, decoded.latitude, 1e-7)
        assertEquals(12.5, decoded.accuracyMeters, 0.001)
    }

    @Test fun `explicit denial has bounded reason and no location`() {
        for (reason in S3LocationWire.reasons) {
            val body = S3LocationWire.body(msg(S3LocationWire.DENIED,
                S3LocationWire.denied(relation, event, messageId, reason)))
            assertEquals(reason, body.denial)
            assertNull(body.encrypted)
        }
        assertTrue(runCatching { S3LocationWire.denied(relation, event, messageId, "secret") }.isFailure)
    }

    @Test fun `coordinates and timestamp have strict boundaries`() {
        assertTrue(runCatching { S3LocationWire.Fix(900_000_001, 0, 100, 100L, "G") }.isFailure)
        assertTrue(runCatching { S3LocationWire.Fix(0, 1_800_000_001, 100, 100L, "G") }.isFailure)
        assertTrue(runCatching { S3LocationWire.Fix(0, 0, 0, 100L, "G") }.isFailure)
        assertTrue(runCatching { S3LocationWire.Fix(0, 0, 100, 0L, "G") }.isFailure)
        assertTrue(runCatching { S3LocationWire.Fix(0, 0, 100, 100L, "PASSIVE") }.isFailure)
    }

    @Test fun `stale fix is rejected as realtime position`() {
        assertTrue(S3LocationWire.fresh(fix, fix.capturedAtMillis + 59_000L))
        assertFalse(S3LocationWire.fresh(fix, fix.capturedAtMillis + 60_001L))
        assertFalse(S3LocationWire.fresh(fix, fix.capturedAtMillis - 31_000L))
    }

    @Test fun `truncated and noncanonical fields cannot be parsed`() {
        val requester = S3LocationCrypto.ephemeral()
        val encoded = S3LocationCrypto.encrypt(fix,
            S3LocationCrypto.encodePublic(requester.public), relation, event, messageId)
        val raw = String(S3LocationWire.result(relation, event, messageId, encoded))
        val bad = listOf(raw.replace("|", "||"), raw + "|x",
            raw.replace("|${encoded.nonce}|", "|INVALID_NONCE|"), raw + "\n",
            "L|$relation|$event|$messageId|0|0|100|1",
            "D|$relation|$event|$messageId|UNSPECIFIED")
        for (invalid in bad) {
            val kind = if (invalid.startsWith("D")) S3LocationWire.DENIED else S3LocationWire.RESULT
            assertTrue(invalid, runCatching { S3LocationWire.body(msg(kind,
                invalid.toByteArray())) }.isFailure)
        }
    }

    @Test fun `event substitution cannot attach coordinates to another request`() {
        val requester = S3LocationCrypto.ephemeral()
        val sealed = S3LocationCrypto.encrypt(fix,
            S3LocationCrypto.encodePublic(requester.public), relation, event, messageId)
        assertTrue(runCatching { S3LocationWire.body(msg(S3LocationWire.RESULT,
            S3LocationWire.result(relation, event, messageId, sealed), UUID.randomUUID().toString()))
        }.isFailure)
    }

    @Test fun `location messages traverse the same signed LAN carrier`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val requester = S3LocationCrypto.ephemeral()
        val encrypted = S3LocationCrypto.encrypt(fix,
            S3LocationCrypto.encodePublic(requester.public), relation, event, messageId)
        val data = msg(S3LocationWire.RESULT,
            S3LocationWire.result(relation, event, messageId, encrypted))
        val signed = SignedEnvelope(data, SignedProtocol.sign(data, pair.private))
        val decoded = LanTestWire.decode(LanTestWire.encode(signed))
        assertTrue(SignedProtocol.verify(decoded.message, decoded.signature, pair.public))
        assertEquals(fix, S3LocationCrypto.decrypt(requester.private,
            requireNotNull(S3LocationWire.body(decoded.message).encrypted),
            relation, event, messageId))
    }

    @Test fun `intercepted LAN position response contains no plaintext coordinates`() {
        val requester = S3LocationCrypto.ephemeral()
        val encrypted = S3LocationCrypto.encrypt(fix,
            S3LocationCrypto.encodePublic(requester.public), relation, event, messageId)
        val raw = String(S3LocationWire.result(relation, event, messageId, encrypted))
        assertFalse(raw.contains(fix.latitudeE7.toString()))
        assertFalse(raw.contains(fix.longitudeE7.toString()))
        assertFalse(raw.contains(fix.accuracyCm.toString()))
    }

    @Test fun `wrong private key or different request cannot decrypt a captured position`() {
        val requester = S3LocationCrypto.ephemeral()
        val encrypted = S3LocationCrypto.encrypt(fix,
            S3LocationCrypto.encodePublic(requester.public), relation, event, messageId)
        assertTrue(runCatching { S3LocationCrypto.decrypt(S3LocationCrypto.ephemeral().private,
            encrypted, relation, event, messageId) }.isFailure)
        assertTrue(runCatching { S3LocationCrypto.decrypt(requester.private,
            encrypted, relation, event, UUID.randomUUID().toString()) }.isFailure)
        val altered = encrypted.copy(ciphertext = encrypted.ciphertext.reversed())
        assertTrue(runCatching { S3LocationCrypto.decrypt(requester.private,
            altered, relation, event, messageId) }.isFailure)
    }
}
