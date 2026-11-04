package io.github.tigaotg.openkinguard.security.protocol

/** S1-only exercise maintenance; never apply these timeouts to real emergency requests. */
object ExerciseMaintenance {
    const val UNCLAIMED_EXPIRY_MS = 24L * 60L * 60L * 1_000L

    fun staleUnclaimed(receivedAt: Long, now: Long): Boolean =
        receivedAt > 0L && receivedAt < now - UNCLAIMED_EXPIRY_MS

    /** Closed events can be resent after reconnect; increasing backoff avoids endless LAN floods. */
    fun shouldRetryClose(lastAttemptAt: Long, attempts: Int, now: Long): Boolean {
        if (lastAttemptAt <= 0L || now < lastAttemptAt) return true
        val exponent = attempts.coerceIn(1, 6)
        val interval = (5_000L shl exponent).coerceAtMost(300_000L)
        return now - lastAttemptAt >= interval
    }
}
