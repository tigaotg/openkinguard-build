package io.github.tigaotg.openkinguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkReconnectPolicyTest {
    @Test fun exponentialBaseIsCappedAtSixtySeconds() {
        assertEquals(2_000L, NetworkReconnectPolicy.baseDelayMillis(1))
        assertEquals(4_000L, NetworkReconnectPolicy.baseDelayMillis(2))
        assertEquals(8_000L, NetworkReconnectPolicy.baseDelayMillis(3))
        assertEquals(60_000L, NetworkReconnectPolicy.baseDelayMillis(20))
    }

    @Test fun equalJitterStaysWithinHalfAndAllOfTheCappedBase() {
        val base = NetworkReconnectPolicy.baseDelayMillis(4)
        val minimum = NetworkReconnectPolicy.delayMillis(4, 0.0)
        val maximum = NetworkReconnectPolicy.delayMillis(4, 1.0)
        assertEquals(base / 2L, minimum)
        assertEquals(base, maximum)
        assertTrue(NetworkReconnectPolicy.delayMillis(30, 0.5) <= 60_000L)
    }

    @Test fun invalidRandomInputIsRejected() {
        runCatching { NetworkReconnectPolicy.delayMillis(1, Double.NaN) }
            .onSuccess { throw AssertionError("NaN jitter must be rejected") }
        runCatching { NetworkReconnectPolicy.delayMillis(1, 1.1) }
            .onSuccess { throw AssertionError("Out-of-range jitter must be rejected") }
    }
}
