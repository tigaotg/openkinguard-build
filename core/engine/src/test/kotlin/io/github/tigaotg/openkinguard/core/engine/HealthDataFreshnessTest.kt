package io.github.tigaotg.openkinguard.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class HealthDataFreshnessTest {
    @Test fun cannotInferMeasurementFromBluetoothConnection() {
        assertEquals(HealthDataFreshness.NO_READING,
            HealthDataFreshnessPolicy.assess(1_000_000L, null))
    }
    @Test fun recentDataBecomesStaleAfterFiveMinutes() {
        assertEquals(HealthDataFreshness.RECENT,
            HealthDataFreshnessPolicy.assess(1_000_000L, 800_000L))
        assertEquals(HealthDataFreshness.STALE,
            HealthDataFreshnessPolicy.assess(1_000_000L, 699_999L))
    }
    @Test fun detectsClockGoingBackwards() {
        assertEquals(HealthDataFreshness.CLOCK_MISMATCH,
            HealthDataFreshnessPolicy.assess(1_000_000L, 1_060_000L))
    }
}
