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
}
