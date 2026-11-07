package io.github.tigaotg.openkinguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundMonitorHealthModelTest {
    private fun healthySnapshot() = BackgroundHealthSnapshot(
        serviceState = BackgroundServiceState.RUNNING,
        schedulerState = BackgroundSchedulerState.HEALTHY,
        schedulerStartedElapsed = 1_000L,
        schedulerGeneration = 1L,
        schedulerClosed = false,
        lastTickElapsed = 10_000L,
        lastSuccessfulTaskElapsed = 10_000L,
        tcpListenerState = BackgroundTcpListenerState.LISTENING,
        networkState = BackgroundNetworkState.NETWORK_AVAILABLE
    )

    @Test fun schedulerTickDoesNotHideTcpListenerFailure() {
        val state = healthySnapshot().copy(
            tcpListenerState = BackgroundTcpListenerState.FAILED
        )
        assertEquals(BackgroundSchedulerState.HEALTHY,
            BackgroundMonitorHealthPolicy.schedulerState(state, 20_000L))
        assertFalse(BackgroundMonitorHealthPolicy.localServiceHealthy(state))
    }

    @Test fun noNetworkDoesNotMeanTheLocalServiceIsDead() {
        val state = healthySnapshot().copy(
            networkState = BackgroundNetworkState.NO_NETWORK
        )
        assertTrue(BackgroundMonitorHealthPolicy.localServiceHealthy(state))
    }

    @Test fun schedulerAgeIsClassifiedAsHealthyDelayedOrStalled() {
        val state = healthySnapshot()
        assertEquals(BackgroundSchedulerState.HEALTHY,
            BackgroundMonitorHealthPolicy.schedulerState(state, 100_000L))
        assertEquals(BackgroundSchedulerState.DELAYED,
            BackgroundMonitorHealthPolicy.schedulerState(state, 101_000L))
        assertEquals(BackgroundSchedulerState.STALLED,
            BackgroundMonitorHealthPolicy.schedulerState(state, 161_000L))
    }

    @Test fun aFailedCriticalTaskOverridesARecentTick() {
        val state = healthySnapshot().copy(
            lastTaskErrorCode = "BACKGROUND_TASK_FAILED",
            lastTaskErrorElapsed = 19_000L
        )
        assertEquals(BackgroundSchedulerState.TASK_FAILED,
            BackgroundMonitorHealthPolicy.schedulerState(state, 20_000L))
        assertFalse(BackgroundMonitorHealthPolicy.localServiceHealthy(state))
    }

    @Test fun aLongRunningTaskIsReportedAsStalled() {
        val state = healthySnapshot().copy(
            taskRunning = true,
            lastTickStartedElapsed = 10_000L,
            lastTickElapsed = 0L
        )
        assertEquals(BackgroundSchedulerState.STALLED,
            BackgroundMonitorHealthPolicy.schedulerState(state, 130_001L))
    }

    @Test fun recoveryUsesBoundedBackoffAndStopsAfterThreeAttempts() {
        val first = BackgroundRecoveryPolicy.evaluate(
            BackgroundConsent.ENABLED, false, 0, 0L, 10_000L
        )
        assertTrue(first.allowed)

        val waiting = BackgroundRecoveryPolicy.evaluate(
            BackgroundConsent.ENABLED, false, 1, 10_000L, 29_999L
        )
        assertFalse(waiting.allowed)
        assertEquals(1_000L, waiting.retryAfterMs)

        val second = BackgroundRecoveryPolicy.evaluate(
            BackgroundConsent.ENABLED, false, 1, 10_000L, 30_000L
        )
        assertTrue(second.allowed)

        val third = BackgroundRecoveryPolicy.evaluate(
            BackgroundConsent.ENABLED, false, 2, 30_000L, 90_000L
        )
        assertTrue(third.allowed)

        val exhausted = BackgroundRecoveryPolicy.evaluate(
            BackgroundConsent.ENABLED, false, 3, 90_000L, 300_000L
        )
        assertFalse(exhausted.allowed)
        assertTrue(exhausted.exhausted)
    }

    @Test fun disabledOrInvalidConsentNeverRecovers() {
        assertFalse(BackgroundRecoveryPolicy.evaluate(
            BackgroundConsent.USER_DISABLED, false, 0, 0L, 10_000L
        ).allowed)
        assertFalse(BackgroundRecoveryPolicy.evaluate(
            BackgroundConsent.CONSENT_INVALID, false, 0, 0L, 10_000L
        ).allowed)
    }
}
