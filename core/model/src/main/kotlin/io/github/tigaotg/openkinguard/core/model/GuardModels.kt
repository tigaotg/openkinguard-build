package io.github.tigaotg.openkinguard.core.model

enum class WallClockConfidence { VERIFIED, UNVERIFIED, KNOWN_INCORRECT }

/** All clock readings are injected by the app; the core never reads system time. */
data class ClockReading(
    val epochMillis: Long,
    val elapsedRealtimeMillis: Long,
    val wallClockConfidence: WallClockConfidence = WallClockConfidence.UNVERIFIED
) {
    init {
        require(epochMillis >= 0)
        require(elapsedRealtimeMillis >= 0)
    }
}

enum class IncidentType { MANUAL_SOS, HEART_RATE_ABNORMAL }
enum class IncidentStatus { OPEN, ACCEPTED, CLOSED }
enum class Resolution { CONFIRMED_SAFE, FALSE_ALARM, PROFESSIONAL_HELP_CONTACTED, OTHER }
enum class HeartRateQuality { VALID, INVALID, NOT_WORN, UNAVAILABLE }
enum class AbnormalDirection { HIGH, LOW }

data class Incident(
    val id: EventId,
    val subjectId: UserId,
    val originDeviceId: DeviceId,
    val type: IncidentType,
    val createdEpochMillis: Long,
    val status: IncidentStatus = IncidentStatus.OPEN,
    val acceptedBy: UserId? = null,
    val resolvedBy: UserId? = null,
    val resolution: Resolution? = null
)

data class HeartRateTrack(
    val direction: AbnormalDirection? = null,
    val sinceElapsedMillis: Long? = null,
    val lastSampleElapsedMillis: Long? = null,
    val raisedEventId: EventId? = null
)

data class HeartRateRule(
    val lowBpm: Int,
    val highBpm: Int,
    val requiredDurationMillis: Long,
    val maximumSampleAgeMillis: Long = 90_000,
    val maximumSampleGapMillis: Long = 90_000,
    val enabled: Boolean = false
) {
    init {
        require(lowBpm > 0 && lowBpm < highBpm)
        require(requiredDurationMillis >= 0)
        require(maximumSampleAgeMillis > 0)
        require(maximumSampleGapMillis > 0)
    }
}

data class GuardRules(
    val heartRateRule: HeartRateRule? = null
)

/** Local device state. Mirrors of a remote subject are stored separately per subject. */
data class GuardState(
    val localUserId: UserId,
    val localDeviceId: DeviceId,
    val relations: Map<RelationId, HelpRelation> = emptyMap(),
    val incidents: Map<EventId, Incident> = emptyMap(),
    val heartRateTracks: Map<UserId, HeartRateTrack> = emptyMap()
)
