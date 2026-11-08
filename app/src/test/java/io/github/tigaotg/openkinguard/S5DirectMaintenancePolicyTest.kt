package io.github.tigaotg.openkinguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S5DirectMaintenancePolicyTest {
    @Test fun noTrustedPeersSkipPeriodicStunAndUseLowFrequencyRefresh() {
        val plan = S5DirectMaintenancePolicy.plan(0)
        assertFalse(plan.probeStun)
        assertEquals(300L, plan.refreshIntervalSeconds)
    }

    @Test fun trustedPeersKeepNatCandidatesFresh() {
        val plan = S5DirectMaintenancePolicy.plan(1)
        assertTrue(plan.probeStun)
        assertEquals(60L, plan.refreshIntervalSeconds)
    }

    @Test fun multiplePeersShareOneBoundedRefreshCadence() {
        assertEquals(S5DirectMaintenancePolicy.plan(1),
            S5DirectMaintenancePolicy.plan(8))
    }

    @Test fun idleUdpReceiveDoesNotWakeOnPeriodicTimeout() {
        assertEquals(0, S5DirectMaintenancePolicy.BLOCKING_RECEIVE_TIMEOUT_MILLIS)
    }

    @Test fun unchangedNetworkKeepsTheExistingUdpSocket() {
        assertFalse(S5DirectMaintenancePolicy.shouldRebindSocket(
            socketActive = true, sameNetwork = true, sameLinkProperties = true
        ))
    }

    @Test fun networkOrLinkPropertiesChangeRebindsTheUdpSocket() {
        assertTrue(S5DirectMaintenancePolicy.shouldRebindSocket(
            socketActive = true, sameNetwork = false, sameLinkProperties = true
        ))
        assertTrue(S5DirectMaintenancePolicy.shouldRebindSocket(
            socketActive = true, sameNetwork = true, sameLinkProperties = false
        ))
    }

    @Test fun missingUdpSocketIsRecoveredEvenWhenNetworkIdentityIsUnchanged() {
        assertTrue(S5DirectMaintenancePolicy.shouldRebindSocket(
            socketActive = false, sameNetwork = true, sameLinkProperties = true
        ))
    }
}
