package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class SignedProtocolTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val wrongKeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val sender = DeviceId("device-a")
    private val receiver = DeviceId("device-b")
    private val now = 1_000_000L

    private fun message(sequence: Long = 1, id: String = "message-1") = MessageEnvelope(
        protocolVersion = 1,
        messageId = MessageId(id),
        eventId = EventId("event-123"),
        senderDeviceId = sender,
        subjectId = UserId("person-a"),
        recipientDeviceId = receiver,
        createdEpochMillis = now,
        expiresEpochMillis = now + 60_000,
        sequence = sequence,
        payloadKind = "SOS",
        payload = "test payload".toByteArray(Charsets.UTF_8),
    )

    private fun signed(m: MessageEnvelope) = SignedEnvelope(m, SignedProtocol.sign(m, keyPair.private))

    @Test fun ownSignatureVerifiesTrustedKeyOnly() {
        val m = message()
        val signature = SignedProtocol.sign(m, keyPair.private)
        assertTrue(SignedProtocol.verify(m, signature, keyPair.public))
        assertFalse(SignedProtocol.verify(m, signature, wrongKeyPair.public))
    }

    @Test fun changingPayloadOrRecipientInvalidatesSignature() {
        val m = message()
        val signature = SignedProtocol.sign(m, keyPair.private)
        assertFalse(SignedProtocol.verify(m.copy(payload = "changed".toByteArray()), signature, keyPair.public))
        assertFalse(SignedProtocol.verify(m.copy(recipientDeviceId = DeviceId("other")), signature, keyPair.public))
        assertFalse(SignedProtocol.verify(m.copy(sequence = 2), signature, keyPair.public))
    }

    @Test fun wireEncodingIsStableAndBoundsChecked() {
        val m = message()
        assertArrayEquals(SignedProtocol.bytesToSign(m), SignedProtocol.bytesToSign(m.copy()))
        try {
            SignedProtocol.bytesToSign(m.copy(payload = ByteArray(65_537)))
            fail("Oversized message must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun unpairedKeyAndWrongRecipientAreRejected() {
        val signed = signed(message())
        val noPair = AuthenticatedAdmission.examine(signed, sender, receiver, null, now, ReplayWindow())
        assertEquals(AdmissionFailure.UNTRUSTED_KEY, (noPair as AdmissionResult.Rejected).reason)
        val wrongDestination = AuthenticatedAdmission.examine(signed, sender, DeviceId("c"),
            keyPair.public, now, ReplayWindow())
        assertEquals(AdmissionFailure.WRONG_RECIPIENT, (wrongDestination as AdmissionResult.Rejected).reason)
    }

    @Test fun aReplayCannotBeAcceptedTwice() {
        val one = signed(message())
        val first = AuthenticatedAdmission.examine(one, sender, receiver, keyPair.public,
            now, ReplayWindow()) as AdmissionResult.Accepted
        val second = AuthenticatedAdmission.examine(one, sender, receiver, keyPair.public,
            now, first.nextWindow) as AdmissionResult.Rejected
        assertEquals(AdmissionFailure.DUPLICATE, second.reason)
    }

    @Test fun outOfOrderMessagesWithinWindowAreAllowedOnce() {
        var window = ReplayWindow()
        for (seq in listOf(10L, 12L, 11L)) {
            val result = AuthenticatedAdmission.examine(signed(message(seq, "id-$seq")), sender, receiver,
                keyPair.public, now, window) as AdmissionResult.Accepted
            window = result.nextWindow
        }
        val replay = AuthenticatedAdmission.examine(signed(message(11L, "id-11b")), sender,
            receiver, keyPair.public, now, window) as AdmissionResult.Rejected
        assertEquals(AdmissionFailure.DUPLICATE, replay.reason)
    }

    @Test fun sequencesOutsideWindowAndExpiredMessagesAreRejected() {
        val high = AuthenticatedAdmission.examine(signed(message(100, "high")), sender, receiver,
            keyPair.public, now, ReplayWindow()) as AdmissionResult.Accepted
        val tooOld = AuthenticatedAdmission.examine(signed(message(1, "old")), sender, receiver,
            keyPair.public, now, high.nextWindow) as AdmissionResult.Rejected
        assertEquals(AdmissionFailure.SEQUENCE_TOO_OLD, tooOld.reason)
        val expired = AuthenticatedAdmission.examine(signed(message()), sender, receiver,
            keyPair.public, now + 60_000, ReplayWindow()) as AdmissionResult.Rejected
        assertEquals(AdmissionFailure.EXPIRED, expired.reason)
    }

    @Test fun futureTimestampAndBadSignatureRejected() {
        val future = signed(message().copy(createdEpochMillis = now + 50_000,
            expiresEpochMillis = now + 60_000))
        val result = AuthenticatedAdmission.examine(future, sender, receiver, keyPair.public,
            now, ReplayWindow()) as AdmissionResult.Rejected
        assertEquals(AdmissionFailure.FUTURE_TIMESTAMP, result.reason)
        val corrupted = SignedEnvelope(message(), byteArrayOf(1, 2, 3))
        val bad = AuthenticatedAdmission.examine(corrupted, sender, receiver, keyPair.public,
            now, ReplayWindow()) as AdmissionResult.Rejected
        assertEquals(AdmissionFailure.BAD_SIGNATURE, bad.reason)
    }
}
