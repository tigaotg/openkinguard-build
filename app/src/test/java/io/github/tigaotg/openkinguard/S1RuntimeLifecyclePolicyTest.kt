package io.github.tigaotg.openkinguard

import org.junit.Assert.*
import org.junit.Test

class S1RuntimeLifecyclePolicyTest {
    @Test fun controllerRetainsTransportAfterOriginalUiStops() {
        assertFalse(S1RuntimeLifecyclePolicy.shouldStop(false, true, false, false))
    }

    @Test fun closingControllerReturnsTransportToOriginalUi() {
        assertFalse(S1RuntimeLifecyclePolicy.shouldStop(true, false, false, false))
    }

    @Test fun noOwnersStopsTransportAndRevokesAssistSession() {
        assertTrue(S1RuntimeLifecyclePolicy.shouldStop(false, false, false, false))
    }

    @Test fun activeForegroundServicesRetainTransport() {
        assertFalse(S1RuntimeLifecyclePolicy.shouldStop(false, false, true, false))
        assertFalse(S1RuntimeLifecyclePolicy.shouldStop(false, false, false, true))
    }

    @Test fun repeatedOwnerTransitionsRemainIdempotentUntilLastOwnerStops() {
        val owners = mutableSetOf<String>()
        fun shouldStop() = S1RuntimeLifecyclePolicy.shouldStop(
            uiVisible = "ui" in owners,
            controllerVisible = "controller" in owners,
            backgroundListening = "service" in owners,
            projectionForeground = "projection" in owners
        )

        owners += "ui"
        assertFalse(shouldStop())
        assertFalse(shouldStop()) // Repeated attach keeps the same active owner.

        owners += "service"
        assertFalse(shouldStop())
        owners -= "ui"
        assertFalse(shouldStop()) // The service still owns the shared transport.

        owners -= "service"
        assertTrue(shouldStop())
        assertTrue(shouldStop()) // Repeated detach remains stopped.
    }
}
