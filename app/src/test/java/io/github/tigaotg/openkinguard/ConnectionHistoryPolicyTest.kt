package io.github.tigaotg.openkinguard

import org.junit.Assert.*
import org.junit.Test

class ConnectionHistoryPolicyTest {
    @Test fun retainsExactlySevenDaysAndRejectsFuture() {
        val now = 1_800_000_000_000L
        assertTrue(ConnectionHistoryPolicy.isRetained(now - ConnectionHistoryPolicy.RETENTION_MS, now))
        assertFalse(ConnectionHistoryPolicy.isRetained(now - ConnectionHistoryPolicy.RETENTION_MS - 1, now))
        assertFalse(ConnectionHistoryPolicy.isRetained(now + 1, now))
    }

    @Test fun periodicSnapshotIsAtMostOncePerMinute() {
        assertTrue(ConnectionHistoryPolicy.shouldSnapshot(0L, 10L))
        assertFalse(ConnectionHistoryPolicy.shouldSnapshot(1000L, 60_999L))
        assertTrue(ConnectionHistoryPolicy.shouldSnapshot(1000L, 61_000L))
    }

    @Test fun identifiesTransitionsAndRemovedPeers() {
        val before = mapOf("network/type" to "wifi", "peer/abc/lan" to "verified")
        val after = mapOf("network/type" to "cellular", "relay/1/socket" to "connected")
        val changes = ConnectionHistoryPolicy.transitions(before, after)
        assertEquals(3, changes.size)
        assertTrue(changes.any { it.path == "peer/abc/lan" && it.after == "removed" })
    }

    @Test fun stripsIpUrlsFingerprintsAndArbitraryErrorText() {
        val data = ConnectionHistoryPolicy.sanitizeState(mapOf(
            "network/type" to "wifi",
            "endpoint" to "https://192.168.1.1:9090/relay",
            "private:key" to "secret",
            "relay/1/failure" to ConnectionHistoryPolicy.failureCategory(
                "timeout wss://192.168.1.1/relay")
        ))
        assertEquals("wifi", data["network/type"])
        assertEquals("timeout", data["relay/1/failure"])
        assertFalse(data.containsKey("endpoint"))
        assertFalse(data.containsKey("private:key"))
        assertFalse(data.values.any { "192.168" in it || "https" in it })
    }

    @Test fun peerIdentityIsPseudonymizedAndStable() {
        val first = ConnectionHistoryPolicy.peerAlias("relation-1")
        assertEquals(first, ConnectionHistoryPolicy.peerAlias("relation-1"))
        assertEquals(12, first.length)
        assertNotEquals(first, ConnectionHistoryPolicy.peerAlias("relation-2"))
    }
}
