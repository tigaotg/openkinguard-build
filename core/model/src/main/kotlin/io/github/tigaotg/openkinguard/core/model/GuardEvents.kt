package io.github.tigaotg.openkinguard.core.model

/**
 * Input events have already passed the authentication/authorization boundary.
 * A real transport adapter MUST verify signatures and anti-replay before producing them.
 */
sealed interface GuardEvent {
    /** Subject's explicit consent and helper's acceptance were verified upstream. */
    data class RelationEstablished(val relation: HelpRelation) : GuardEvent
    data class RelationWithdrawn(val relationId: RelationId, val actorId: UserId) : GuardEvent

    data class ManualSosRequested(
        val eventId: EventId,
        val subjectId: UserId,
        val originDeviceId: DeviceId
    ) : GuardEvent

    /** The elapsed-time timestamp must be expressed on the local device's clock. */
    data class HeartRateObserved(
        val eventIdForNewAlert: EventId,
        val subjectId: UserId,
        val originDeviceId: DeviceId,
        val bpm: Int?,
        val quality: HeartRateQuality,
        val sampledAtElapsedMillis: Long
    ) : GuardEvent

    /** A validated announcement from a different subject's device. */
    data class RemoteIncidentAnnounced(val incident: Incident) : GuardEvent

    /** Received on the subject's own device; only it may decide the winner. */
    data class TakeoverRequested(val eventId: EventId, val helperId: UserId) : GuardEvent

    data class ClosureRequested(
        val eventId: EventId,
        val actorId: UserId,
        val resolution: Resolution,
        val explicitlyConfirmed: Boolean
    ) : GuardEvent
}

sealed interface GuardCommand {
    /** A command does not mean a message was transmitted or delivered. */
    data class DispatchEmergency(
        val eventId: EventId,
        val recipientIds: Set<UserId>
    ) : GuardCommand

    data class BroadcastCaseChanged(
        val eventId: EventId,
        val recipientIds: Set<UserId>
    ) : GuardCommand

    data class WarnLocally(val warning: LocalWarning) : GuardCommand
}

enum class LocalWarning { NO_AUTHORIZED_HELPERS }

data class Decision(
    val state: GuardState,
    val commands: List<GuardCommand> = emptyList()
)
