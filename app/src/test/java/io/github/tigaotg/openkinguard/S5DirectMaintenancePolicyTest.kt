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
}
