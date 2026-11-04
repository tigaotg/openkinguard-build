package io.github.tigaotg.openkinguard.core.contracts

import io.github.tigaotg.openkinguard.core.model.*

/** Capability claims must be honest; unavailable is not a heart-rate value of zero. */
data class DataSourceCapabilities(
    val supportsLiveSamples: Boolean,
    val supportsHistoricalSamples: Boolean,
    val nominalMinimumUpdateMillis: Long?,
    val requiresUserActionToStart: Boolean
)

data class HeartRateSample(
    val bpm: Int?,
    val quality: HeartRateQuality,
    val sampledAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val sourceId: String,
    val supportsLiveMonitoring: Boolean
)

data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val sampledAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val sourceId: String
) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        require(accuracyMeters >= 0)
    }
}

interface HeartRateSource {
    val capabilities: DataSourceCapabilities
    /** Emissions are observations only, never emergency decisions. */
    fun latestSample(): HeartRateSample?
}

interface LocationSource {
    /** May return null when permission, hardware or a fresh fix is unavailable. */
    fun latestFix(): GeoFix?
}

/** The caller handles session keys, authorization, protocol negotiation and replay checks. */
interface MessageTransport {
    fun enqueue(message: MessageEnvelope): Boolean
}

/** An implementation must persist logical MessageId before retrying. */
interface OutboxStore {
    fun insertIfAbsent(entry: OutboxEntry): Boolean
    fun pending(): List<OutboxEntry>
    fun recordReceipt(receipt: DeliveryReceipt): Boolean
}

/** Explicitly separates an intent to alert from a real-world delivery result. */
interface EmergencyDelivery {
    fun prepare(eventId: EventId, recipients: Set<UserId>): DeliveryPreparation
}

data class DeliveryPreparation(
    val availableRoutes: Set<String>,
    val paidActionRequiresConfirmation: Boolean,
    val isActualDelivery: Boolean = false
)

interface GuardStateStore {
    fun load(): GuardState?
    fun save(state: GuardState)
}

interface DiagnosticLog {
    /** Do not include coordinates, phone numbers, tokens, private keys or raw heart-rate history. */
    fun record(code: String, occurredAtEpochMillis: Long)
}
