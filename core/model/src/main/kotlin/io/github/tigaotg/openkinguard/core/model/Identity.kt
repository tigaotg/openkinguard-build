package io.github.tigaotg.openkinguard.core.model

/** Stable identifiers are created outside the decision engine. */
@JvmInline
value class UserId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class DeviceId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class RelationId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class EventId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class MessageId(val value: String) {
    init { require(value.isNotBlank()) }
}

/** Public identity descriptor only; private signing keys never belong in GuardState. */
data class DeviceIdentity(
    val userId: UserId,
    val deviceId: DeviceId,
    val signingKeyFingerprint: String,
    val createdEpochMillis: Long
) {
    init {
        require(signingKeyFingerprint.isNotBlank())
        require(createdEpochMillis >= 0)
    }
}

enum class RelationPermission {
    RECEIVE_ALERTS,
    RESPOND_TO_ALERTS,
    VIEW_LOCATION,
    VIEW_HEART_RATE,
    REQUEST_WELLNESS_CHECK,
    MANAGE_REGULAR_SETTINGS
}

enum class RelationStatus { ACTIVE, REVOKED }

/**
 * A directed relationship. A helping B does not imply B helping A.
 * No fixed elderly/family account types exist.
 */
data class HelpRelation(
    val id: RelationId,
    val subjectId: UserId,
    val helperId: UserId,
    val permissions: Set<RelationPermission>,
    val status: RelationStatus = RelationStatus.ACTIVE
) {
    init { require(subjectId != helperId) { "Use local monitoring instead of a self-relation" } }

    fun authorizes(user: UserId, permission: RelationPermission): Boolean =
        status == RelationStatus.ACTIVE &&
            helperId == user &&
            permission in permissions
}
