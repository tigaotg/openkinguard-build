package io.github.tigaotg.openkinguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTcpListenerRecoveryPolicyTest {
    @Test fun healthyAndBindingListenersAreNotRestarted() {
        assertFalse(BackgroundTcpListenerRecoveryPolicy.shouldAttempt(
            BackgroundConsent.ENABLED, BackgroundTcpListenerState.LISTENING))
        assertFalse(BackgroundTcpListenerRecoveryPolicy.shouldAttempt(
            BackgroundConsent.ENABLED, BackgroundTcpListenerState.BINDING))
    }

    @Test fun stoppedOrFailedListenersRecoverOnlyWithExistingConsent() {
        val broken = listOf(
            BackgroundTcpListenerState.NOT_STARTED,
            BackgroundTcpListenerState.FAILED,
            BackgroundTcpListenerState.CLOSED_UNEXPECTEDLY
        )
        broken.forEach { state ->
            assertTrue(BackgroundTcpListenerRecoveryPolicy.shouldAttempt(
                BackgroundConsent.ENABLED, state))
            assertFalse(BackgroundTcpListenerRecoveryPolicy.shouldAttempt(
                BackgroundConsent.USER_DISABLED, state))
            assertFalse(BackgroundTcpListenerRecoveryPolicy.shouldAttempt(
                BackgroundConsent.NOT_DECIDED, state))
            assertFalse(BackgroundTcpListenerRecoveryPolicy.shouldAttempt(
                BackgroundConsent.CONSENT_INVALID, state))
        }
    }
}
