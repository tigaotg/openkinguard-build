package io.github.tigaotg.openkinguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTcpListenerRecoveryPolicyTest {
    @Test fun healthyAndBindingListenersAreNotRestarted() {
        assertFalse(BackgroundTcpListenerRecoveryPolicy.shouldAttempt(
            BackgroundTcpListenerState.LISTENING))
        assertFalse(BackgroundTcpListenerRecoveryPolicy.shouldAttempt(
            BackgroundTcpListenerState.BINDING))
    }

    @Test fun stoppedOrFailedListenersAreRebuiltByTheService() {
        assertTrue(BackgroundTcpListenerRecoveryPolicy.shouldAttempt(
            BackgroundTcpListenerState.NOT_STARTED))
        assertTrue(BackgroundTcpListenerRecoveryPolicy.shouldAttempt(
            BackgroundTcpListenerState.FAILED))
        assertTrue(BackgroundTcpListenerRecoveryPolicy.shouldAttempt(
            BackgroundTcpListenerState.CLOSED_UNEXPECTEDLY))
    }
}
