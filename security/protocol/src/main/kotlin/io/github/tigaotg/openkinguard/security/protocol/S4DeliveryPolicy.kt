package io.github.tigaotg.openkinguard.security.protocol

/** Durable client outbox policy. Relay RAM queues are an optional short-lived optimization. */
object S4DeliveryPolicy {
    const val MESSAGE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1_000
    const val MAX_OUTBOX_ENTRIES = 32
    const val MAX_FRAME_BYTES = 8_192

    fun retryDelayMillis(attemptCount: Int): Long = when {
        attemptCount <= 1 -> 5_000L
        attemptCount == 2 -> 15_000L
        attemptCount == 3 -> 30_000L
        attemptCount == 4 -> 60_000L
        attemptCount == 5 -> 120_000L
        else -> 300_000L
    }

    fun isDue(nextAttemptAtMillis: Long, expiresAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis >= nextAttemptAtMillis && nowMillis < expiresAtMillis

    fun isExpired(expiresAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis >= expiresAtMillis
}
