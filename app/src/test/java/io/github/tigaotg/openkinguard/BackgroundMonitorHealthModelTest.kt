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

    @Test fun timedOutTaskRemainsStalledEvenAfterItsSchedulerIsClosed() {
        val state = healthySnapshot().copy(
            schedulerClosed = true,
            taskRunning = true,
            lastTickStartedElapsed = 10_000L
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

    @Test fun unexpectedTaskAbortIsRecordedAsFailure() {
        BackgroundMonitorHealth.markSchedulerStopped()
        BackgroundMonitorHealth.markServiceStarting(1L)
        BackgroundMonitorHealth.markServiceRunning(2L)
        BackgroundMonitorHealth.markSchedulerStarted(3L)
        val generation = BackgroundMonitorHealth.beginTick(4L)
        assertTrue(generation != null)

        BackgroundMonitorHealth.abandonTick(generation!!, 5L)
        val state = BackgroundMonitorHealth.snapshot(6L)
        assertFalse(state.taskRunning)
        assertEquals(BackgroundSchedulerState.TASK_FAILED, state.schedulerState)
        assertEquals("BACKGROUND_TASK_ABORTED", state.lastTaskErrorCode)
    }

    @Test fun startupStageHasHumanReadableLabels() {
        assertEquals("TCP 监听绑定",
            BackgroundStartupStage.TCP_LISTENER_BIND.displayLabel)
        assertEquals("后台调度器启动",
            BackgroundStartupStage.SCHEDULER_START.displayLabel)
    }

    @Test fun processExitDiagnosticsDoNotOverstateForceStopDetection() {
        val userRequested = BackgroundProcessExitSnapshot(
            kind = BackgroundProcessExitKind.USER_REQUESTED
        )
        assertTrue(userRequested.displayLabel.contains("可能是强行停止"))
        assertTrue(userRequested.displayLabel.contains("最近任务移除"))
        assertTrue(BackgroundProcessExitSnapshot().displayLabel.contains("Android 10"))
        assertEquals("应用升级导致旧进程退出",
            BackgroundProcessExitSnapshot(
                kind = BackgroundProcessExitKind.PACKAGE_UPDATED
            ).displayLabel)
    }

    @Test fun recoveryTriggerLabelsKeepSystemEventsSeparate() {
        assertEquals("设备启动广播",
            BackgroundRecoveryTrigger.BOOT_COMPLETED.displayLabel)
        assertEquals("应用升级广播",
            BackgroundRecoveryTrigger.PACKAGE_REPLACED.displayLabel)
        assertEquals("Android START_STICKY 恢复",
            BackgroundRecoveryTrigger.SYSTEM_STICKY_RESTART.displayLabel)
    }

    @Test fun oldSchedulerCompletionCannotMarkReplacementHealthy() {
        BackgroundMonitorHealth.markSchedulerStopped()
        BackgroundMonitorHealth.resetRecoveryAttempts()
        BackgroundMonitorHealth.markServiceStarting(1L)
        BackgroundMonitorHealth.markServiceRunning(2L)
        BackgroundMonitorHealth.markSchedulerStarted(3L)

        val oldGeneration = BackgroundMonitorHealth.beginTick(4L)
        assertTrue(oldGeneration != null)
        assertTrue(BackgroundMonitorHealth.beginRecoveryAttempt(
            BackgroundConsent.ENABLED, healthy = false, nowElapsed = 5L
        ).allowed)
        BackgroundMonitorHealth.markServiceRunning(6L)
        assertEquals(1, BackgroundMonitorHealth.snapshot(6L).recoveryAttemptCount)

        BackgroundMonitorHealth.markSchedulerStopped()
        assertTrue(BackgroundMonitorHealth.snapshot(7L).taskRunning)
        BackgroundMonitorHealth.markSchedulerStarted(8L)
        assertTrue(BackgroundMonitorHealth.snapshot(8L).taskRunning)

        BackgroundMonitorHealth.finishTick(oldGeneration!!, 9L)
        val afterOldTask = BackgroundMonitorHealth.snapshot(9L)
        assertFalse(afterOldTask.taskRunning)
        assertEquals(BackgroundSchedulerState.DELAYED, afterOldTask.schedulerState)
        assertEquals(1, afterOldTask.recoveryAttemptCount)

        val newGeneration = BackgroundMonitorHealth.beginTick(10L)
        assertTrue(newGeneration != null)
        BackgroundMonitorHealth.finishTick(newGeneration!!, 11L)
        val recovered = BackgroundMonitorHealth.snapshot(11L)
        assertEquals(BackgroundSchedulerState.HEALTHY, recovered.schedulerState)
        assertEquals(0, recovered.recoveryAttemptCount)
    }
}
