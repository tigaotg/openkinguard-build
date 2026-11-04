package io.github.tigaotg.openkinguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardWidgetPresencePolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun unverifiedDeviceMustNeverBeShownOnline() {
        assertFalse(GuardWidgetPresencePolicy.isSignedOnline(now - 10_000, false,
            now, 3, 10))
    }

    @Test fun recentAuthenticatedHeartbeatMayBeShownOnline() {
        assertTrue(GuardWidgetPresencePolicy.isSignedOnline(now - 10_000, true,
            now, 3, 10))
    }

    @Test fun staleSessionMustBeHidden() {
        assertFalse(GuardWidgetPresencePolicy.isSignedOnline(now - 15 * 60_000, true,
            now, 3, 10))
    }

    @Test fun widgetCacheExpiresAndStopsWithMonitor() {
        val policy = GuardWidgetPresencePolicy
        assertTrue(policy.isCachedSnapshotFresh(now - 30_000, true, now))
        assertFalse(policy.isCachedSnapshotFresh(now - 180_000, true, now))
        assertFalse(policy.isCachedSnapshotFresh(now - 30_000, false, now))
        assertFalse(policy.isCachedSnapshotFresh(now + 30_000, true, now))
        assertFalse(policy.isCachedSnapshotFresh(0L, true, now))
    }
}
