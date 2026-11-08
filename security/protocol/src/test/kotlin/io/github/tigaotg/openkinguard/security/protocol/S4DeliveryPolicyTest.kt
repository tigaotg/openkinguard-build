package io.github.tigaotg.openkinguard.security.protocol

import org.junit.Assert.*
import org.junit.Test

class S4DeliveryPolicyTest {
    @Test fun retryBackoffIsBoundedAndMonotonic() {
        val delays = (1..6).map(S4DeliveryPolicy::retryDelayMillis)
        assertEquals(listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L), delays)
        assertEquals(300_000L, S4DeliveryPolicy.retryDelayMillis(100))
    }

    @Test fun dueAndExpiryAreDistinct() {
        assertTrue(S4DeliveryPolicy.isDue(100L, 500L, 100L))
        assertFalse(S4DeliveryPolicy.isDue(101L, 500L, 100L))
        assertFalse(S4DeliveryPolicy.isDue(0L, 100L, 100L))
        assertFalse(S4DeliveryPolicy.isExpired(500L, 499L))
        assertTrue(S4DeliveryPolicy.isExpired(500L, 500L))
    }
}
