package io.github.tigaotg.openkinguard.security.protocol

/** Durable client outbox policy. Relay RAM queues are an optional short-lived optimization. */
object S4DeliveryPolicy {
    const val MESSAGE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1_000
    const val MAX_OUTBOX_ENTRIES = 32
    const val MAX_FRAME_BYTES = 8_192
    /** One signed, QR-pinned ECDH card must outlive the seven-day offline SOS queue. */
    const val KEY_CARD_TTL_MILLIS = 30L * 24 * 60 * 60 * 1_000
    const val KEY_CARD_FUTURE_SKEW_MILLIS = 60_000L

    /** Duplicate requests share an ID; reissued signed receipts must have fresh IDs. */
    fun usesStableMessageId(kind: String): Boolean =
        kind in S1CaseWire.requestKinds && kind in S1CaseWire.encryptedKinds

    /** New signature and sequence, but the same logical ID, on every persisted retry. */
    fun shouldResealForRetry(attemptCount: Int, expiresAtMillis: Long, nowMillis: Long): Boolean =
        attemptCount > 0 && nowMillis < expiresAtMillis

    fun isValidKeyCardWindow(issuedAtMillis: Long, expiresAtMillis: Long,
                             nowMillis: Long): Boolean {
        if (issuedAtMillis < nowMillis - KEY_CARD_TTL_MILLIS ||
            issuedAtMillis > nowMillis + KEY_CARD_FUTURE_SKEW_MILLIS) return false
        return expiresAtMillis > nowMillis && expiresAtMillis > issuedAtMillis &&
            expiresAtMillis - issuedAtMillis <= KEY_CARD_TTL_MILLIS
    }

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
