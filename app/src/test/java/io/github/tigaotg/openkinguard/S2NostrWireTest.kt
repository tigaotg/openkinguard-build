package io.github.tigaotg.openkinguard

import io.github.tigaotg.openkinguard.security.protocol.NostrSchnorr
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class S2NostrWireTest {
    @Test fun signedEventRouteAndTamper() {
        val secret = NostrSchnorr.createSecret()
        val destination = "a".repeat(64)
        val expiration = System.currentTimeMillis() + 45_000
        val event = S2NostrWire.sign(secret, destination,
            JSONObject().put("type", "contact").put("payload", "not-a-secret").toString(),
            "card-test", expiration)
        assertTrue(S2NostrWire.verify(event))
        assertEquals(destination, S2NostrWire.routeTag(event))
        val tags = event.getJSONArray("tags")
        for (i in 0 until tags.length()) {
            val tag = tags.getJSONArray(i)
            for (j in 0 until tag.length()) {
                assertTrue("NIP-01 tags must consist only of strings", tag.get(j) is String)
            }
        }
        val expiry = tags.getJSONArray(2)
        assertEquals("expiration", expiry.getString(0))
        assertEquals((expiration / 1000L).toString(), expiry.getString(1))
        val tampered = JSONObject(event.toString()).put("content", "modified")
        assertFalse(S2NostrWire.verify(tampered))
        val changedSignature = JSONObject(event.toString()).put("sig", "0".repeat(128))
        assertFalse(S2NostrWire.verify(changedSignature))
    }

    @Test fun rejectNumericExpirationTagEvenIfCryptographicallyResigned() {
        val secret = NostrSchnorr.createSecret()
        val event = S2NostrWire.sign(secret, "b".repeat(64),
            JSONObject().put("type", "contact").put("payload", "just-diagnostic").toString(),
            "card-test", System.currentTimeMillis() + 45_000)
        event.getJSONArray("tags").getJSONArray(2).put(1, System.currentTimeMillis() / 1000L + 45)
        val canonical = JSONArray().put(0).put(event.getString("pubkey"))
            .put(event.getLong("created_at")).put(event.getInt("kind"))
            .put(event.getJSONArray("tags")).put(event.getString("content"))
            .toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical)
        event.put("id", digest.toHex())
        event.put("sig", NostrSchnorr.sign(digest, secret).toHex())
        assertFalse(S2NostrWire.verify(event))
    }

    @Test fun rejectExpiredContactTag() {
        val secret = NostrSchnorr.createSecret()
        val now = System.currentTimeMillis()
        val event = S2NostrWire.sign(secret, "c".repeat(64),
            JSONObject().put("type", "contact").put("payload", "just-diagnostic").toString(),
            "card-test", now + 1_000)
        assertFalse(S2NostrWire.verify(event, now + 10_000))
    }

    @Test fun noBareHttpOrPrivateRelayPath() {
        assertEquals(listOf("wss://relay.nostr.net", "wss://relay.nostr.info",
            "wss://relay.kizuna-miki.com"),
            S2NostrTransport.normalizeList(S2NostrTransport.DEFAULT_RELAYS))
        assertEquals(3, S2NostrTransport.normalizeList(
            S2NostrTransport.MAINLAND_ALTERNATIVE_RELAYS).size)
        assertThrows(IllegalArgumentException::class.java) {
            S2NostrTransport.normalizeList("ws://relay.primal.net")
        }
        assertThrows(IllegalArgumentException::class.java) {
            S2NostrTransport.normalizeList("wss://myrelay.com/relay")
        }
        assertThrows(IllegalArgumentException::class.java) {
            S2NostrTransport.normalizeList(S2NostrTransport.DEFAULT_RELAYS + "\nwss://nos.lol")
        }
    }

    @Test fun upgradeBundledPresetWithoutOverwritingCustomList() {
        assertEquals(S2NostrTransport.DEFAULT_RELAYS,
            S2NostrTransport.migrateBundledDefaults("wss://relay.primal.net\nwss://nos.lol"))
        assertEquals(S2NostrTransport.DEFAULT_RELAYS,
            S2NostrTransport.migrateBundledDefaults(
                "wss://relay.nostr.net\nwss://relay.nostr.info\nwss://relay.nostr.wirednet.jp"))
        val customized = "wss://relay.nostr.info\nwss://relay.kizuna-miki.com"
        assertEquals(customized, S2NostrTransport.migrateBundledDefaults(customized))
    }

    private fun ByteArray.toHex(): String = joinToString("") {
        "%02x".format(it.toInt() and 255)
    }
}
