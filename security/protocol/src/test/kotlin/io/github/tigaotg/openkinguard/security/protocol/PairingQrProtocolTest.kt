package io.github.tigaotg.openkinguard.security.protocol

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class PairingQrProtocolTest {
    private val a = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val b = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val now = 1_000_000L

    private fun qr(step: PairingStep, own: java.security.KeyPair, prior: VerifiedPairingQr? = null) =
        PairingQrProtocol.decodeAndVerify(PairingQrProtocol.encode(
            PairingQrProtocol.newFrame(step, own.public.encoded, prior = prior, nowEpochMillis = now),
            own.private), now)

    private fun assertReject(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun fourStepFramesHaveLinkedValidSignatures() {
        val invite = qr(PairingStep.INVITE, a)
        val response = qr(PairingStep.RESPONSE, b, invite)
        PairingQrProtocol.requireNext(invite, response)
        val grant = qr(PairingStep.GRANT, a, response)
        PairingQrProtocol.requireNext(response, grant)
        val ack = qr(PairingStep.ACK, b, grant)
        PairingQrProtocol.requireNext(grant, ack)
        assertEquals(PairingStep.ACK, ack.frame.step)
        assertEquals(invite.frame.sessionId, ack.frame.sessionId)
    }

    @Test fun modifiedQrFailsSignatureVerification() {
        val original = qr(PairingStep.INVITE, a).encodedQr
        val pieces = original.split('.')
        assertReject {
            val payload = java.util.Base64.getUrlDecoder().decode(pieces.first().removePrefix("okg-pair-v1:"))
            payload[payload.lastIndex] = (payload.last().toInt() xor 0x40).toByte()
            PairingQrProtocol.decodeAndVerify("okg-pair-v1:" +
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "." + pieces.last(), now)
        }
    }

    @Test fun expiredQrIsRejected() {
        val original = qr(PairingStep.INVITE, a).encodedQr
        assertReject { PairingQrProtocol.decodeAndVerify(original, now + 600_000) }
    }

    @Test fun wrongSessionAndPermissionsAreRejected() {
        val invite = qr(PairingStep.INVITE, a)
        val response = qr(PairingStep.RESPONSE, b, invite)
        val otherSession = PairingQrProtocol.decodeAndVerify(PairingQrProtocol.encode(
            response.frame.copy(sessionId = java.util.UUID.randomUUID().toString()), b.private), now)
        assertReject { PairingQrProtocol.requireNext(invite, otherSession) }
        assertReject {
            PairingQrProtocol.encode(invite.frame.copy(permissionBits = 999), a.private)
        }
    }

    @Test fun unrelatedResponseCannotBeSubstituted() {
        val invite1 = qr(PairingStep.INVITE, a)
        val invite2 = qr(PairingStep.INVITE, a)
        val response2 = qr(PairingStep.RESPONSE, b, invite2)
        assertReject { PairingQrProtocol.requireNext(invite1, response2) }
    }

    @Test fun responseAddressedToWrongFingerprintRejected() {
        val invite = qr(PairingStep.INVITE, a)
        val response = qr(PairingStep.RESPONSE, b, invite)
        val forged = PairingQrProtocol.decodeAndVerify(PairingQrProtocol.encode(
            response.frame.copy(recipientFingerprint = "f".repeat(64)), b.private), now)
        assertReject { PairingQrProtocol.requireNext(invite, forged) }
    }

    @Test fun wrongQrHeaderAndOversizedQrRejected() {
        assertReject { PairingQrProtocol.decodeAndVerify("https://example.com", now) }
        assertReject { PairingQrProtocol.decodeAndVerify("okg-pair-v1:" + "a".repeat(3_000), now) }
    }

    @Test fun p256IsRequired() {
        val other = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp384r1"))
        }.generateKeyPair()
        assertReject { PairingQrProtocol.publicKey(other.public.encoded) }
    }
}
