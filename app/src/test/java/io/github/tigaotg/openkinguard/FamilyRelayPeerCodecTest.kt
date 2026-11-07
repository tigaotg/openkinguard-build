package io.github.tigaotg.openkinguard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FamilyRelayPeerCodecTest {
    @Test fun familyRelayTargetRoundTrips() {
        val peer = FamilyRelayPeer("a".repeat(64), 2)
        assertEquals(peer, FamilyRelayPeerCodec.decode(FamilyRelayPeerCodec.encode(peer)))
    }

    @Test fun invalidFingerprintCannotBePersisted() {
        assertThrows(IllegalArgumentException::class.java) {
            FamilyRelayPeerCodec.encode(FamilyRelayPeer("not-a-fingerprint", 0))
        }
    }

    @Test fun invalidNodeSlotCannotBeRestored() {
        assertThrows(IllegalArgumentException::class.java) {
            FamilyRelayPeerCodec.decode(JSONObject().put("fingerprint", "b".repeat(64)).put("slot", 3))
        }
    }
}
