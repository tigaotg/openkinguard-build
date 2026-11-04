package io.github.tigaotg.openkinguard.core.engine

import io.github.tigaotg.openkinguard.core.model.*

/**
 * Deterministic, side-effect-free S0 decision engine.
 *
 * It cannot call a network, Bluetooth, Android service or emergency number.
 * Commands are intentions, NOT proof that another human has received an alert.
 */
class DecisionEngine {
    fun evaluate(state: GuardState, event: GuardEvent, rules: GuardRules, clock: ClockReading): Decision =
        when (event) {
            is GuardEvent.RelationEstablished -> establishRelation(state, event)
            is GuardEvent.RelationWithdrawn -> withdrawRelation(state, event)
            is GuardEvent.ManualSosRequested -> createSos(state, event, clock)
            is GuardEvent.HeartRateObserved -> observeHeartRate(state, event, rules, clock)
            is GuardEvent.RemoteIncidentAnnounced -> announceRemoteIncident(state, event)
            is GuardEvent.TakeoverRequested -> requestTakeover(state, event)
            is GuardEvent.ClosureRequested -> requestClosure(state, event)
        }

    private fun establishRelation(state: GuardState, event: GuardEvent.RelationEstablished): Decision {
        val relation = event.relation
        if (state.localUserId != relation.subjectId && state.localUserId != relation.helperId) return Decision(state)
        // A revoked relationship ID cannot be silently reactivated: create a new authorized ID.
        if (relation.id in state.relations || relation.status != RelationStatus.ACTIVE) return Decision(state)
        return Decision(state.copy(relations = state.relations + (relation.id to relation)))
    }

    private fun withdrawRelation(state: GuardState, event: GuardEvent.RelationWithdrawn): Decision {
        val relation = state.relations[event.relationId] ?: return Decision(state)
        if (relation.status != RelationStatus.ACTIVE) return Decision(state)
        if (event.actorId != relation.subjectId && event.actorId != relation.helperId) return Decision(state)
        return Decision(state.copy(relations = state.relations + (relation.id to relation.copy(status = RelationStatus.REVOKED))))
    }

    private fun createSos(state: GuardState, event: GuardEvent.ManualSosRequested, clock: ClockReading): Decision {
        if (event.subjectId != state.localUserId || event.originDeviceId != state.localDeviceId) return Decision(state)
        if (event.eventId in state.incidents) return Decision(state)
        val incident = Incident(
            id = event.eventId,
            subjectId = event.subjectId,
            originDeviceId = event.originDeviceId,
            type = IncidentType.MANUAL_SOS,
            createdEpochMillis = clock.epochMillis
        )
        return newIncident(state, incident)
    }

    private fun observeHeartRate(
        state: GuardState,
        event: GuardEvent.HeartRateObserved,
        rules: GuardRules,
        clock: ClockReading
    ): Decision {
        if (event.subjectId != state.localUserId || event.originDeviceId != state.localDeviceId) return Decision(state)
        val rule = rules.heartRateRule?.takeIf { it.enabled } ?: return Decision(state)
        val age = clock.elapsedRealtimeMillis - event.sampledAtElapsedMillis
        val bpm = event.bpm
        if (event.quality != HeartRateQuality.VALID || bpm == null || bpm !in 1..300 ||
            age !in 0..rule.maximumSampleAgeMillis
        ) return resetHeartRateTrack(state, event.subjectId)

        val direction = when {
            bpm < rule.lowBpm -> AbnormalDirection.LOW
            bpm > rule.highBpm -> AbnormalDirection.HIGH
            else -> null
        }
        if (direction == null) return resetHeartRateTrack(state, event.subjectId)

        val old = state.heartRateTracks[event.subjectId]
        val lastSample = old?.lastSampleElapsedMillis
        val continuous = old?.direction == direction &&
            old.sinceElapsedMillis != null &&
            lastSample != null &&
            event.sampledAtElapsedMillis >= lastSample &&
            event.sampledAtElapsedMillis - lastSample <= rule.maximumSampleGapMillis
        val newTrack = if (continuous) {
            old.copy(lastSampleElapsedMillis = event.sampledAtElapsedMillis)
        } else {
            HeartRateTrack(
                direction = direction,
                sinceElapsedMillis = event.sampledAtElapsedMillis,
                lastSampleElapsedMillis = event.sampledAtElapsedMillis
            )
        }

        val tracked = state.copy(heartRateTracks = state.heartRateTracks + (event.subjectId to newTrack))
        if (newTrack.raisedEventId != null ||
            event.sampledAtElapsedMillis - newTrack.sinceElapsedMillis!! < rule.requiredDurationMillis ||
            event.eventIdForNewAlert in tracked.incidents
        ) return Decision(tracked)

        val incident = Incident(
            id = event.eventIdForNewAlert,
            subjectId = event.subjectId,
            originDeviceId = event.originDeviceId,
            type = IncidentType.HEART_RATE_ABNORMAL,
            createdEpochMillis = clock.epochMillis
        )
        val raisedState = tracked.copy(heartRateTracks = tracked.heartRateTracks +
            (event.subjectId to newTrack.copy(raisedEventId = incident.id)))
        return newIncident(raisedState, incident)
    }

    private fun resetHeartRateTrack(state: GuardState, subjectId: UserId): Decision =
        if (subjectId !in state.heartRateTracks) Decision(state)
        else Decision(state.copy(heartRateTracks = state.heartRateTracks - subjectId))

    private fun announceRemoteIncident(state: GuardState, event: GuardEvent.RemoteIncidentAnnounced): Decision {
        val incident = event.incident
        if (incident.subjectId == state.localUserId || incident.id in state.incidents) return Decision(state)
        if (state.relations.values.none {
                it.subjectId == incident.subjectId &&
                    it.authorizes(state.localUserId, RelationPermission.RECEIVE_ALERTS)
            }) return Decision(state)
        return Decision(state.copy(incidents = state.incidents + (incident.id to incident)))
    }

    private fun requestTakeover(state: GuardState, event: GuardEvent.TakeoverRequested): Decision {
        val incident = state.incidents[event.eventId] ?: return Decision(state)
        if (incident.subjectId != state.localUserId || incident.status != IncidentStatus.OPEN) return Decision(state)
        if (state.relations.values.none {
                it.subjectId == incident.subjectId &&
                    it.authorizes(event.helperId, RelationPermission.RESPOND_TO_ALERTS)
            }) return Decision(state)
        val accepted = incident.copy(status = IncidentStatus.ACCEPTED, acceptedBy = event.helperId)
        return Decision(
            state.copy(incidents = state.incidents + (incident.id to accepted)),
            listOf(GuardCommand.BroadcastCaseChanged(incident.id, authorizedHelpers(state, incident.subjectId)))
        )
    }

    private fun requestClosure(state: GuardState, event: GuardEvent.ClosureRequested): Decision {
        val incident = state.incidents[event.eventId] ?: return Decision(state)
        if (!event.explicitlyConfirmed || incident.status == IncidentStatus.CLOSED) return Decision(state)
        // S0 policy: the subject or the verified accepted helper may explicitly close.
        // All remote operations MUST be authenticated by the receiving boundary.
        val permitted = event.actorId == incident.subjectId ||
            (event.actorId == incident.acceptedBy && state.relations.values.any {
                it.subjectId == incident.subjectId &&
                    it.authorizes(event.actorId, RelationPermission.RESPOND_TO_ALERTS)
            })
        if (!permitted) return Decision(state)
        val closed = incident.copy(
            status = IncidentStatus.CLOSED, resolvedBy = event.actorId, resolution = event.resolution
        )
        return Decision(
            state.copy(incidents = state.incidents + (incident.id to closed)),
            listOf(GuardCommand.BroadcastCaseChanged(incident.id, authorizedHelpers(state, incident.subjectId)))
        )
    }

    private fun newIncident(state: GuardState, incident: Incident): Decision {
        val updated = state.copy(incidents = state.incidents + (incident.id to incident))
        val recipients = authorizedHelpers(state, incident.subjectId)
        return if (recipients.isEmpty()) {
            Decision(updated, listOf(GuardCommand.WarnLocally(LocalWarning.NO_AUTHORIZED_HELPERS)))
        } else {
            Decision(updated, listOf(GuardCommand.DispatchEmergency(incident.id, recipients)))
        }
    }

    private fun authorizedHelpers(state: GuardState, subject: UserId): Set<UserId> =
        state.relations.values.asSequence()
            .filter { it.subjectId == subject && it.status == RelationStatus.ACTIVE }
            .filter { RelationPermission.RECEIVE_ALERTS in it.permissions }
            .map { it.helperId }.toSet()
}
