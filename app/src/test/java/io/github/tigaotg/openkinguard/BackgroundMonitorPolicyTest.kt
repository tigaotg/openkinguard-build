package io.github.tigaotg.openkinguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundMonitorPolicyTest {
    @Test fun explicitlyDisabledNeverAutomaticallyRecovers() {
        assertFalse(BackgroundMonitorPolicy.mayAutoRecover(
            BackgroundConsent.USER_DISABLED, healthy = false))
        assertEquals(BackgroundPhase.USER_DISABLED, BackgroundMonitorPolicy.phase(
            BackgroundConsent.USER_DISABLED, false, false, false))
    }

    @Test fun firstRunRequiresConsent() {
        assertFalse(BackgroundMonitorPolicy.mayAutoRecover(
            BackgroundConsent.NOT_DECIDED, healthy = false))
        assertEquals(BackgroundPhase.NOT_ENABLED, BackgroundMonitorPolicy.phase(
            BackgroundConsent.NOT_DECIDED, false, false, false))
    }

    @Test fun previouslyEnabledAndStoppedShouldRecover() {
        assertTrue(BackgroundMonitorPolicy.mayAutoRecover(
            BackgroundConsent.ENABLED, healthy = false))
        assertEquals(BackgroundPhase.RECOVERING, BackgroundMonitorPolicy.phase(
            BackgroundConsent.ENABLED, false, true, false))
        assertEquals(BackgroundPhase.FAILED, BackgroundMonitorPolicy.phase(
            BackgroundConsent.ENABLED, false, false, false))
    }

    @Test fun runningHeartbeatIsDistinctFromSystemBatteryRestrictions() {
        assertEquals(BackgroundPhase.RUNNING, BackgroundMonitorPolicy.phase(
            BackgroundConsent.ENABLED, true, false, true))
        assertEquals(BackgroundPhase.SYSTEM_RESTRICTED, BackgroundMonitorPolicy.phase(
            BackgroundConsent.ENABLED, false, false, true))
        assertFalse(BackgroundMonitorPolicy.mayAutoRecover(
            BackgroundConsent.ENABLED, healthy = true))
    }

    @Test fun invalidConsentFailsClosed() {
        assertFalse(BackgroundMonitorPolicy.mayAutoRecover(
            BackgroundConsent.CONSENT_INVALID, healthy = false))
        assertEquals(BackgroundPhase.CONSENT_INVALID, BackgroundMonitorPolicy.phase(
            BackgroundConsent.CONSENT_INVALID, false, false, false))
    }
}
