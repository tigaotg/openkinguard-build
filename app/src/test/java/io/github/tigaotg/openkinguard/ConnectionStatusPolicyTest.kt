package io.github.tigaotg.openkinguard

import org.junit.Assert.*
import org.junit.Test

class ConnectionStatusPolicyTest {
    @Test fun anyRecentlyVerifiedRouteMeansReachable() {
        assertTrue(ConnectionStatusPolicy.isReachable(true, false, false))
        assertTrue(ConnectionStatusPolicy.isReachable(false, true, false))
        assertTrue(ConnectionStatusPolicy.isReachable(false, false, true))
        assertTrue(ConnectionStatusPolicy.isReachable(true, true, true))
    }

    @Test fun noVerifiedRouteMustNotClaimPhoneIsPoweredOff() {
        assertFalse(ConnectionStatusPolicy.isReachable(false, false, false))
        assertEquals("暂未确认可达（不代表手机关机）",
            ConnectionStatusPolicy.reachabilityLabel(false, false, false))
    }

    @Test fun staleLastMessageIsNotUsedAsCurrentRouteProof() {
        assertFalse(ConnectionStatusPolicy.isReachable(false, false, false))
        assertTrue(ConnectionStatusPolicy.isReachable(false, true, false))
    }
}
