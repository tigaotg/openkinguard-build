package io.github.tigaotg.openkinguard

import java.util.UUID

/**
 * In-memory, fail-closed human-consent state. Never persists across app restarts.
 * No state here authorizes MediaProjection or AccessibilityService by itself.
 */
internal class RemoteAssistConsent(private val clock: () -> Long = System::currentTimeMillis) {
    enum class Direction { INCOMING, OUTGOING }
    enum class Phase { WAITING_FOR_PERSON, WAITING_FOR_REPLY, AWAITING_SCREEN_PERMISSION, ACTIVE }

    data class Session(
        val relationId: String,
        val peerFingerprint: String,
        val sessionId: String,
        val direction: Direction,
        val phase: Phase,
        val expiresAt: Long,
    )

    private var current: Session? = null
    private val inviteLifetimeMillis = 60_000L
    private val permissionLifetimeMillis = 15 * 60_000L

    @Synchronized fun snapshot(): Session? {
        val item = current ?: return null
        if (clock() >= item.expiresAt) {
            current = null
            return null
        }
        return item
    }

    /** Called by a real user action on the helper phone, before signaling. */
    @Synchronized fun request(relationId: String, peerFingerprint: String): Session {
        require(snapshot() == null) { "请先结束当前远程协助请求" }
        return Session(relationId, peerFingerprint, UUID.randomUUID().toString(),
            Direction.OUTGOING, Phase.WAITING_FOR_REPLY,
            clock() + inviteLifetimeMillis).also { current = it }
    }

    /** The authenticated request only creates a prompt; it NEVER gives permission. */
    @Synchronized fun receiveRequest(relationId: String, peerFingerprint: String,
                                     sessionId: String): Boolean {
        val existing = snapshot()
        if (existing != null) return existing.direction == Direction.INCOMING &&
            existing.sessionId == sessionId && existing.relationId == relationId &&
            existing.peerFingerprint == peerFingerprint
        current = Session(relationId, peerFingerprint, sessionId,
            Direction.INCOMING, Phase.WAITING_FOR_PERSON,
            clock() + inviteLifetimeMillis)
        return true
    }

    /** Must be called exclusively from the local user's Agree/Reject button. */
    @Synchronized fun localDecision(sessionId: String, approve: Boolean): Session {
        val before = requireNotNull(snapshot()) { "远程协助请求已超时" }
        require(before.sessionId == sessionId && before.direction == Direction.INCOMING &&
            before.phase == Phase.WAITING_FOR_PERSON) { "不存在可供本人确认的请求" }
        if (approve) {
            current = before.copy(phase = Phase.AWAITING_SCREEN_PERMISSION,
                expiresAt = clock() + permissionLifetimeMillis)
        } else current = null
        return before
    }

    /** Remote agreement still does not start capture; system-level consent is separate. */
    @Synchronized fun remoteDecision(relationId: String, peerFingerprint: String,
                                     sessionId: String, approved: Boolean): Boolean {
        val before = snapshot() ?: return false
        if (before.relationId != relationId || before.peerFingerprint != peerFingerprint ||
            before.sessionId != sessionId || before.direction != Direction.OUTGOING ||
            before.phase != Phase.WAITING_FOR_REPLY) return false
        if (approved) current = before.copy(phase = Phase.AWAITING_SCREEN_PERMISSION,
            expiresAt = clock() + permissionLifetimeMillis)
        else current = null
        return true
    }

    @Synchronized fun stop(relationId: String, peerFingerprint: String,
                           sessionId: String): Boolean {
        val before = snapshot() ?: return false
        if (before.relationId != relationId || before.peerFingerprint != peerFingerprint ||
            before.sessionId != sessionId) return false
        current = null
        return true
    }

    /** Invoked only after the local user's Android screen-capture grant succeeds. */
    @Synchronized fun screenStarted(sessionId: String): Boolean {
        val before = snapshot() ?: return false
        if (before.sessionId != sessionId || before.direction != Direction.INCOMING ||
            before.phase != Phase.AWAITING_SCREEN_PERMISSION) return false
        current = before.copy(phase = Phase.ACTIVE)
        return true
    }

    /** A verified preview is proof the consenting peer began projection. */
    @Synchronized fun remotePreviewStarted(sessionId: String): Boolean {
        val before = snapshot() ?: return false
        if (before.sessionId != sessionId || before.direction != Direction.OUTGOING ||
            before.phase !in setOf(Phase.AWAITING_SCREEN_PERMISSION, Phase.ACTIVE)) return false
        current = before.copy(phase = Phase.ACTIVE)
        return true
    }

    @Synchronized fun clear() { current = null }
}
