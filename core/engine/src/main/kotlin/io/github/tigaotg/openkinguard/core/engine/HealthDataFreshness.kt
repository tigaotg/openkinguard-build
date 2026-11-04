package io.github.tigaotg.openkinguard.core.engine

enum class HealthDataFreshness { NO_READING, RECENT, STALE, CLOCK_MISMATCH }

/** A connection flag is never proof of an up-to-date measurement. */
object HealthDataFreshnessPolicy {
    fun assess(now: Long, latestAt: Long?, recentWindowMs: Long = 5 * 60_000L): HealthDataFreshness {
        if (latestAt == null) return HealthDataFreshness.NO_READING
        if (latestAt > now + 5_000L) return HealthDataFreshness.CLOCK_MISMATCH
        if (now - latestAt > recentWindowMs) return HealthDataFreshness.STALE
        return HealthDataFreshness.RECENT
    }
}
