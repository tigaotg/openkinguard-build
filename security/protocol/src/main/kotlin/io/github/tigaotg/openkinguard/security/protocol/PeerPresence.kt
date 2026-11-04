package io.github.tigaotg.openkinguard.security.protocol

/** Only a signed exchange with the pinned key counts as a verified heartbeat. */
enum class PeerPresenceLevel { NEVER_SEEN, RECENT, WAITING, WARNING, CRITICAL }

object PeerPresence {
    const val WARNING_AFTER_MS = 3 * 60_000L
    const val CRITICAL_AFTER_MS = 10 * 60_000L

    /** User-adjusted timeouts affect alerts only, not the signed 90s online threshold. */
    fun level(lastVerifiedAt: Long?, now: Long,
              warningMinutes: Int = 3, criticalMinutes: Int = 10): PeerPresenceLevel {
        require(warningMinutes in 3..30 && criticalMinutes in (warningMinutes + 1)..60) {
            "Invalid offline warning thresholds"
        }
        if (lastVerifiedAt == null || lastVerifiedAt <= 0L) return PeerPresenceLevel.NEVER_SEEN
        val age = (now - lastVerifiedAt).coerceAtLeast(0L)
        return when {
            age >= criticalMinutes * 60_000L -> PeerPresenceLevel.CRITICAL
            age >= warningMinutes * 60_000L -> PeerPresenceLevel.WARNING
            age >= 90_000L -> PeerPresenceLevel.WAITING
            else -> PeerPresenceLevel.RECENT
        }
    }

    fun warningStage(lastVerifiedAt: Long?, now: Long,
                     warningMinutes: Int = 3, criticalMinutes: Int = 10): Int =
        when (level(lastVerifiedAt, now, warningMinutes, criticalMinutes)) {
        PeerPresenceLevel.WARNING -> 1
        PeerPresenceLevel.CRITICAL -> 2
        else -> 0
    }
}
