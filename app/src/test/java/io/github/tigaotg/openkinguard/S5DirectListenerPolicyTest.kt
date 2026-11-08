package io.github.tigaotg.openkinguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S5DirectListenerPolicyTest {
    @Test fun listenerDoesNotRestoreWithoutExplicitSavedConsent() {
        assertFalse(S5DirectListenerPolicy.shouldRestore(false, true, true))
    }

    @Test fun listenerDoesNotRestoreWhenPublicSignalingWasDisabled() {
        assertFalse(S5DirectListenerPolicy.shouldRestore(true, false, true))
    }

    @Test fun listenerDoesNotRestoreWithoutLocalForegroundService() {
        assertFalse(S5DirectListenerPolicy.shouldRestore(true, true, false))
    }

    @Test fun listenerRestoresOnlyWhenAllIndependentConditionsHold() {
        assertTrue(S5DirectListenerPolicy.shouldRestore(true, true, true))
    }
}
