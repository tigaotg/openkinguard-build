package io.github.tigaotg.openkinguard.core.engine

/**
 * S5 protocol policy, not yet a network transport.
 * Public servers may ONLY carry messages of these types. An unknown kind is
 * not silently treated as encrypted signaling: it is rejected.
 */
enum class S5SignalKind {
    PRESENCE, CANDIDATES, CONNECT_REQUEST, CONNECT_RESPONSE, STOP, RENEGOTIATE,
}

enum class S5DirectState {
    DIRECT_READY, DISCONNECTED, NEGOTIATING, UNREACHABLE,
}

/** No arbitrary application frame, GPS value, or SOS body can be placed here. */
data class S5RendezvousSignal(
    val kind: S5SignalKind,
    val sessionId: String,
    val senderFingerprint: String,
    val recipientFingerprint: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val sequence: Long,
) {
    init {
        require(sessionId.matches(Regex("[0-9a-f]{32}")))
        require(senderFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(recipientFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(senderFingerprint != recipientFingerprint)
        require(sequence >= 0)
        require(createdAtMillis > 0 && expiresAtMillis - createdAtMillis in 1L..30_000L)
    }
}

/**
 * Pure deterministic scheduling; the Android adapter must supply an elapsed-
 * realtime clock, rather than wall time that can jump on system time changes.
 */
object S5DirectOnlyPolicy {
    const val DEFAULT_RETRY_SECONDS = 10
    const val FAST_RETRY_SECONDS = 5

    fun parseSignalKind(raw: String): S5SignalKind? =
        S5SignalKind.entries.firstOrNull { it.name == raw }

    fun shouldAttemptRendezvous(
        state: S5DirectState,
        lastAttemptElapsedMillis: Long?,
        nowElapsedMillis: Long,
        intervalSeconds: Int = DEFAULT_RETRY_SECONDS,
    ): Boolean {
        require(intervalSeconds == FAST_RETRY_SECONDS ||
            intervalSeconds == DEFAULT_RETRY_SECONDS)
        require(nowElapsedMillis >= 0)
        if (state == S5DirectState.DIRECT_READY) return false
        if (lastAttemptElapsedMillis == null) return true
        // After clock/process reset, a new negotiation may begin immediately.
        return nowElapsedMillis < lastAttemptElapsedMillis ||
            nowElapsedMillis - lastAttemptElapsedMillis >= intervalSeconds * 1_000L
    }

    /** An ICE/TLS relay cannot be treated as a direct peer on a signed ACK alone. */
    fun directReady(iceSucceeded: Boolean, peerIdentityVerified: Boolean,
                    peerSignedAckReceived: Boolean): Boolean =
        iceSucceeded && peerIdentityVerified && peerSignedAckReceived
}
