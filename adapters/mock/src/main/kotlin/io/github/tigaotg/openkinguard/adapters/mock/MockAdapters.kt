package io.github.tigaotg.openkinguard.adapters.mock

import io.github.tigaotg.openkinguard.core.contracts.*
import io.github.tigaotg.openkinguard.core.model.*

/** Repeatable fake data source. All output is visibly labelled S0 simulation. */
class MockHeartRateSource : HeartRateSource {
    override val capabilities = DataSourceCapabilities(
        supportsLiveSamples = true,
        supportsHistoricalSamples = false,
        nominalMinimumUpdateMillis = 30_000,
        requiresUserActionToStart = false
    )

    var simulatedSample: HeartRateSample? = null
    override fun latestSample(): HeartRateSample? = simulatedSample
}

class MockLocationSource : LocationSource {
    var simulatedFix: GeoFix? = null
    override fun latestFix(): GeoFix? = simulatedFix
}

/** Never sends a packet. The return value is intentionally false. */
class OfflineMockTransport : MessageTransport {
    override fun enqueue(message: MessageEnvelope): Boolean = false
}

/** Records no attempt to contact real people or place calls. */
class MockEmergencyDelivery : EmergencyDelivery {
    override fun prepare(eventId: EventId, recipients: Set<UserId>): DeliveryPreparation =
        DeliveryPreparation(emptySet(), paidActionRequiresConfirmation = true, isActualDelivery = false)
}

class InMemoryGuardStateStore : GuardStateStore {
    private var current: GuardState? = null
    override fun load(): GuardState? = current
    override fun save(state: GuardState) { current = state }
}

/** S0 outbox mock; not a substitute for persistent crash-safe outbox in S2. */
class InMemoryOutboxStore : OutboxStore {
    private val entries = linkedMapOf<MessageId, OutboxEntry>()
    override fun insertIfAbsent(entry: OutboxEntry): Boolean {
        if (entry.envelope.messageId in entries) return false
        entries[entry.envelope.messageId] = entry
        return true
    }

    override fun pending(): List<OutboxEntry> = entries.values.filter {
        it.status == DeliveryStatus.QUEUED || it.status == DeliveryStatus.IN_FLIGHT
    }

    override fun recordReceipt(receipt: DeliveryReceipt): Boolean {
        if (!receipt.verified || receipt.messageId !in entries) return false
        val old = entries.getValue(receipt.messageId)
        if (receipt.remoteDeviceId != old.envelope.recipientDeviceId) return false
        if (old.status == DeliveryStatus.ACKNOWLEDGED) return false
        entries[receipt.messageId] = old.copy(status = DeliveryStatus.ACKNOWLEDGED)
        return true
    }
}
