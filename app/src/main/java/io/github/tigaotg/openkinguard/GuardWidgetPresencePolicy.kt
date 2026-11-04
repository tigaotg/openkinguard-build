package io.github.tigaotg.openkinguard

import io.github.tigaotg.openkinguard.security.protocol.PeerPresence
import io.github.tigaotg.openkinguard.security.protocol.PeerPresenceLevel

/** Fail closed: a stale heartbeat or a missing verified session is not online. */
internal object GuardWidgetPresencePolicy {
    const val MAX_SNAPSHOT_AGE_MS = 120_000L

    fun isSignedOnline(lastVerifiedAt: Long?, authenticatedNow: Boolean, now: Long,
        warningMinutes: Int, criticalMinutes: Int): Boolean = authenticatedNow &&
        PeerPresence.level(lastVerifiedAt, now, warningMinutes,
            maxOf(warningMinutes + 1, criticalMinutes)) == PeerPresenceLevel.RECENT

    fun isCachedSnapshotFresh(checkedAt: Long, monitoring: Boolean, now: Long): Boolean =
        monitoring && checkedAt > 0L && now - checkedAt in 0..MAX_SNAPSHOT_AGE_MS
}
