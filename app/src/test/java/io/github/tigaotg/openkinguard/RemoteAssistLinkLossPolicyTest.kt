package io.github.tigaotg.openkinguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteAssistLinkLossPolicyTest {
    @Test fun localNetworkLossOnlyAfterGracePeriod() {
        val policy = RemoteAssistLinkLossPolicy()
        assertNull(policy.observe(100, false, true, false))
        assertNull(policy.observe(3_900, false, true, false))
        assertEquals(RemoteAssistLinkLoss.LOCAL_NETWORK,
            policy.observe(4_100, false, true, false))
    }

    @Test fun transientHandoverDoesNotEndAssist() {
        val policy = RemoteAssistLinkLossPolicy()
        assertNull(policy.observe(0, false, true, false))
        assertNull(policy.observe(2_000, true, true, false))
        assertNull(policy.observe(5_000, false, true, false))
        assertNull(policy.observe(7_500, true, true, false))
    }

    @Test fun onlyEstablishedMediaIsConsideredDisconnected() {
        val policy = RemoteAssistLinkLossPolicy()
        assertNull(policy.observe(0, true, false, true))
        assertNull(policy.observe(60_000, true, false, true))
        assertNull(policy.observe(61_000, true, true, true))
        assertEquals(RemoteAssistLinkLoss.REMOTE_PEER,
            policy.observe(73_000, true, true, true))
    }

    @Test fun recoveredPeerResetsLossTimer() {
        val policy = RemoteAssistLinkLossPolicy()
        assertNull(policy.observe(0, true, true, true))
        assertNull(policy.observe(10_000, true, true, false))
        assertNull(policy.observe(11_000, true, true, true))
        assertNull(policy.observe(21_000, true, true, true))
    }

    @Test fun bothFailuresPreferLocalExplanation() {
        val policy = RemoteAssistLinkLossPolicy()
        assertNull(policy.observe(0, false, true, true))
        assertEquals(RemoteAssistLinkLoss.LOCAL_NETWORK,
            policy.observe(12_500, false, true, true))
    }
}
