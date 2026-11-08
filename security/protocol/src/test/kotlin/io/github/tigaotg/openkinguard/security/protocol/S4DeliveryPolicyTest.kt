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
    @Test fun onlyEncryptedRequestsHaveStableLogicalIds() {
        for (kind in S1CaseWire.requestKinds) {
            assertEquals(kind != S1CaseWire.PROFILE,
                S4DeliveryPolicy.usesStableMessageId(kind))
        }
        for (kind in S1CaseWire.responseKinds) {
            assertFalse("Receipts require independently replay-safe identifiers",
                S4DeliveryPolicy.usesStableMessageId(kind))
        }
    }

    @Test fun persistedRetriesResealUntilTheirOriginalExpiry() {
        assertFalse(S4DeliveryPolicy.shouldResealForRetry(0, 1_000L, 100L))
        assertTrue(S4DeliveryPolicy.shouldResealForRetry(1, 1_000L, 100L))
        assertTrue(S4DeliveryPolicy.shouldResealForRetry(20, 1_000L, 999L))
        assertFalse(S4DeliveryPolicy.shouldResealForRetry(20, 1_000L, 1_000L))
    }

    @Test fun pinnedKeyCardWindowCoversOfflineOutboxWithoutAllowingUnboundedValidity() {
        val now = 1_800_000_000_000L
        val ttl = S4DeliveryPolicy.KEY_CARD_TTL_MILLIS
        assertTrue(ttl > S4DeliveryPolicy.MESSAGE_TTL_MILLIS)
        assertTrue(S4DeliveryPolicy.isValidKeyCardWindow(now - 1_000L, now + ttl - 1_000L, now))
        assertTrue(S4DeliveryPolicy.isValidKeyCardWindow(now - 1_000L, now + 60_000L, now))
        assertFalse(S4DeliveryPolicy.isValidKeyCardWindow(now - ttl - 1L, now + 1L, now))
        assertFalse(S4DeliveryPolicy.isValidKeyCardWindow(now + 60_001L, now + 70_000L, now))
        assertFalse(S4DeliveryPolicy.isValidKeyCardWindow(now, now + ttl + 1L, now))
        assertFalse(S4DeliveryPolicy.isValidKeyCardWindow(now - 60_000L, now, now))
    }
}
