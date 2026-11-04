package io.github.tigaotg.openkinguard

import io.github.tigaotg.openkinguard.security.protocol.PairingQrProtocol
import io.github.tigaotg.openkinguard.security.protocol.PairingStep
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class RemotePairingInvitationCodecTest {
    private fun pair(now: Long): Pair<String, JSONObject> {
        val keys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val frame = PairingQrProtocol.newFrame(PairingStep.INVITE,
            keys.public.encoded, nowEpochMillis = now)
        val encoded = PairingQrProtocol.encode(frame, keys.private)
        val card = JSONObject().put("fp", frame.senderFingerprint)
            .put("npub", "ab".repeat(32)).put("epub", "test-public")
        return encoded to card
    }

    @Test fun signedInviteRoundTripKeepsStableRandomRoomAndRelayHints() {
        val now = System.currentTimeMillis()
        val (frame, card) = pair(now)
        val link = RemotePairingInvitationCodec.encode(frame, card,
            listOf("wss://relay.nostr.net"), now)
        val parsed = RemotePairingInvitationCodec.parse(link, now + 100L)
        assertEquals(frame, parsed.frame)
        assertEquals(link, parsed.link)
        assertEquals(listOf("wss://relay.nostr.net"), parsed.relays)
        assertTrue(parsed.room.matches(Regex("[0-9a-f]{64}")))
        assertEquals(parsed.room, RemotePairingInvitationCodec.room(
            PairingQrProtocol.decodeAndVerify(frame, now).frame.sessionId))
        assertFalse(link.contains("test-public")) // URL payload is encoded, not plaintext.
    }

    @Test fun expiredInviteAndInvalidLinkMustFailBeforePairing() {
        val now = System.currentTimeMillis()
        val (frame, card) = pair(now)
        val link = RemotePairingInvitationCodec.encode(frame, card,
            listOf("wss://relay.nostr.net"), now)
        assertThrows(IllegalArgumentException::class.java) {
            RemotePairingInvitationCodec.parse(link, now + 11 * 60_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemotePairingInvitationCodec.parse("https://unknown.example/invite")
        }
    }

    @Test fun handshakeAadBindsRoomStepAndExpiration() {
        val room = RemotePairingInvitationCodec.room("unique-session-id")
        val a = RemotePairingInvitationCodec.aad(room, "RESPONSE", 100_000L)
        val b = RemotePairingInvitationCodec.aad(room, "ACK", 100_000L)
        val c = RemotePairingInvitationCodec.aad(room, "RESPONSE", 100_001L)
        assertFalse(a.contentEquals(b))
        assertFalse(a.contentEquals(c))
        assertThrows(IllegalArgumentException::class.java) {
            RemotePairingInvitationCodec.aad(room, "SOS", 100_000L)
        }
    }
}
