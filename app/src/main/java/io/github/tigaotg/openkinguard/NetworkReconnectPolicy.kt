package io.github.tigaotg.openkinguard

/** Shared, bounded equal-jitter backoff for network transports. Attempt numbers are 1-based. */
internal object NetworkReconnectPolicy {
    const val BASE_DELAY_MILLIS = 2_000L
    const val MAX_DELAY_MILLIS = 60_000L

    fun baseDelayMillis(attempt: Int): Long {
        val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(10)
        val multiplier = 1L shl exponent
        return (BASE_DELAY_MILLIS * multiplier).coerceAtMost(MAX_DELAY_MILLIS)
    }

    /** Returns a delay in [50%, 100%] of the capped exponential base. */
    fun delayMillis(attempt: Int, jitterUnit: Double): Long {
        require(jitterUnit.isFinite() && jitterUnit in 0.0..1.0)
        val base = baseDelayMillis(attempt)
        val lower = (base / 2L).coerceAtLeast(1L)
        return lower + ((base - lower) * jitterUnit).toLong()
    }
}
