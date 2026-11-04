package io.github.tigaotg.openkinguard.adapters.mock

import io.github.tigaotg.openkinguard.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MockAdaptersTest {
    private val source = DeviceId("subject-device")
    private val target = DeviceId("helper-device")
    private val envelope = MessageEnvelope(
        protocolVersion = 1,
        messageId = MessageId("logical-message-1"),
        eventId = EventId("case-1"),
        senderDeviceId = source,
        subjectId = UserId("person-a"),
        recipientDeviceId = target,
        createdEpochMillis = 1000,
        expiresEpochMillis = 60000,
        sequence = 1,
        payloadKind = "TEST_ONLY",
        payload = byteArrayOf(1, 2, 3)
    )

    @Test fun `mock transport never transmits a message`() {
        assertFalse(OfflineMockTransport().enqueue(envelope))
    }

    @Test fun `mock emergency delivery is never actual delivery`() {
        val prep = MockEmergencyDelivery().prepare(EventId("case-1"), setOf(UserId("helper")))
        assertFalse(prep.isActualDelivery)
        assertTrue(prep.paidActionRequiresConfirmation)
        assertTrue(prep.availableRoutes.isEmpty())
    }

    @Test fun `same logical message id cannot be reinserted with new bytes`() {
        val store = InMemoryOutboxStore()
        assertTrue(store.insertIfAbsent(OutboxEntry(envelope, DeliveryPriority.EMERGENCY, DeliveryStatus.QUEUED)))
        assertFalse(store.insertIfAbsent(OutboxEntry(envelope.copy(payload = byteArrayOf(9)), DeliveryPriority.EMERGENCY, DeliveryStatus.QUEUED)))
        assertEquals(1, store.pending().size)
    }

    @Test fun `a receipt must be verified and issued by the intended destination`() {
        val store = InMemoryOutboxStore()
        store.insertIfAbsent(OutboxEntry(envelope, DeliveryPriority.EMERGENCY, DeliveryStatus.QUEUED))
        assertFalse(store.recordReceipt(DeliveryReceipt(envelope.messageId, target, false, 2000)))
        assertFalse(store.recordReceipt(DeliveryReceipt(envelope.messageId, DeviceId("someone-else"), true, 2000)))
        assertEquals(1, store.pending().size)
        assertTrue(store.recordReceipt(DeliveryReceipt(envelope.messageId, target, true, 2000)))
        assertTrue(store.pending().isEmpty())
        assertFalse(store.recordReceipt(DeliveryReceipt(envelope.messageId, target, true, 2001)))
    }

    @Test fun `message equality compares bytes and all identity fields`() {
        assertEquals(envelope, envelope.copy(payload = byteArrayOf(1, 2, 3)))
        assertNotEquals(envelope, envelope.copy(payload = byteArrayOf(1, 2, 4)))
    }
}
