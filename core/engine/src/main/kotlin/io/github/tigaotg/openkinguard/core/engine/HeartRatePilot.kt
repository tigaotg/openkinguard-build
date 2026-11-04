package io.github.tigaotg.openkinguard.core.engine

/**
 * Foreground-only, non-medical experiment. Nothing here sends an emergency message.
 * All times must be from a monotonic clock (Android elapsedRealtime on the device).
 */
data class HeartRatePilotSettings(
    val enabled: Boolean = false,
    val lowBpm: Int = 50,
    val highBpm: Int = 120,
    val durationMillis: Long = 180_000L,
    val maxSampleGapMillis: Long = 90_000L
) {
    init {
        require(lowBpm in 30..100) { "invalid low threshold" }
        require(highBpm in 80..220 && highBpm > lowBpm) { "invalid high threshold" }
        require(durationMillis in 60_000L..600_000L) { "invalid duration" }
        require(maxSampleGapMillis in 10_000L..180_000L) { "invalid maximum sample gap" }
    }
}

enum class HeartRatePilotPhase {
    OFF, DISCONNECTED, NO_DATA, NORMAL, COUNTING_LOW, COUNTING_HIGH,
    ALERT_LOW, ALERT_HIGH, STALE
}

data class HeartRatePilotStatus(
    val phase: HeartRatePilotPhase,
    val lastBpm: Int? = null,
    val lastSampleElapsedMillis: Long? = null,
    val abnormalSinceElapsedMillis: Long? = null,
    val consecutiveSamples: Int = 0
)

data class HeartRatePilotAlert(
    val low: Boolean,
    val bpm: Int,
    val durationMillis: Long
)

/**
 * Requires multiple fresh, consecutive real measurements over the full duration.
 * Stale samples, clock discontinuity, invalid values, disconnection and a threshold
 * change all break the run. Only one event is emitted per continuous abnormal run.
 */
class HeartRatePilotEvaluator(initial: HeartRatePilotSettings = HeartRatePilotSettings()) {
    var settings: HeartRatePilotSettings = initial
        private set

    private var lastSampleAt: Long? = null
    private var lastBpm: Int? = null
    private var abnormalSince: Long? = null
    private var sampleCount = 0
    private var direction = 0 // -1 low, 0 normal, 1 high
    private var notified = false

    fun configure(next: HeartRatePilotSettings) {
        if (settings == next) return
        settings = next
        reset()
    }

    fun reset() {
        lastSampleAt = null
        lastBpm = null
        abnormalSince = null
        sampleCount = 0
        direction = 0
        notified = false
    }

    /** Return a single locally actionable alert only when a new episode qualifies. */
    fun observe(bpm: Int, elapsedMillis: Long): HeartRatePilotAlert? {
        if (!settings.enabled) return null
        if (elapsedMillis < 0 || bpm !in 20..260) {
            reset()
            return null
        }
        val previous = lastSampleAt
        if (previous != null && elapsedMillis <= previous) {
            // Duplicate and backward timestamps cannot extend an abnormal episode.
            reset()
            return null
        }
        val gap = previous?.let { elapsedMillis - it }
        if (gap != null && gap > settings.maxSampleGapMillis) reset()
        lastSampleAt = elapsedMillis
        lastBpm = bpm

        val nextDirection = when {
            bpm < settings.lowBpm -> -1
            bpm > settings.highBpm -> 1
            else -> 0
        }
        if (nextDirection == 0) {
            direction = 0
            abnormalSince = null
            sampleCount = 0
            notified = false
            return null
        }
        if (direction != nextDirection || abnormalSince == null) {
            direction = nextDirection
            abnormalSince = elapsedMillis
            sampleCount = 1
            notified = false
            return null
        }
        sampleCount++
        if (notified || sampleCount < 2 ||
            elapsedMillis - abnormalSince!! < settings.durationMillis) return null
        notified = true
        return HeartRatePilotAlert(nextDirection < 0, bpm, settings.durationMillis)
    }

    fun snapshot(nowElapsedMillis: Long, connected: Boolean): HeartRatePilotStatus {
        if (!settings.enabled) return HeartRatePilotStatus(HeartRatePilotPhase.OFF)
        if (!connected) return HeartRatePilotStatus(HeartRatePilotPhase.DISCONNECTED)
        val last = lastSampleAt ?: return HeartRatePilotStatus(HeartRatePilotPhase.NO_DATA)
        val age = nowElapsedMillis - last
        val phase = when {
            age < 0L || age > settings.maxSampleGapMillis -> HeartRatePilotPhase.STALE
            direction < 0 && notified -> HeartRatePilotPhase.ALERT_LOW
            direction > 0 && notified -> HeartRatePilotPhase.ALERT_HIGH
            direction < 0 -> HeartRatePilotPhase.COUNTING_LOW
            direction > 0 -> HeartRatePilotPhase.COUNTING_HIGH
            else -> HeartRatePilotPhase.NORMAL
        }
        return HeartRatePilotStatus(phase, lastBpm, lastSampleAt,
            if (phase == HeartRatePilotPhase.STALE) null else abnormalSince,
            if (phase == HeartRatePilotPhase.STALE) 0 else sampleCount)
    }
}
