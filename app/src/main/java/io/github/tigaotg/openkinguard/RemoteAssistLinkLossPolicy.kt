package io.github.tigaotg.openkinguard

/** A grace period prevents brief Wi-Fi/mobile handovers from revoking a session. */
internal enum class RemoteAssistLinkLoss { LOCAL_NETWORK, REMOTE_PEER }

/** Pure, session-scoped link failure detector. Only evaluate after consent is ACTIVE. */
internal class RemoteAssistLinkLossPolicy(
    private val localGraceMillis: Long = 4_000L,
    private val peerGraceMillis: Long = 12_000L,
) {
    private var localOfflineSince: Long? = null
    private var peerDisconnectedSince: Long? = null

    fun observe(now: Long, hasNetwork: Boolean, mediaConnectedOnce: Boolean,
                iceDisconnected: Boolean): RemoteAssistLinkLoss? {
        if (hasNetwork) localOfflineSince = null
        else if (localOfflineSince == null) localOfflineSince = now

        // Initial ICE negotiation may legitimately take time. Only a connection
        // that was genuinely established may be considered subsequently lost.
        if (mediaConnectedOnce && iceDisconnected) {
            if (peerDisconnectedSince == null) peerDisconnectedSince = now
        } else peerDisconnectedSince = null

        if (localOfflineSince?.let { now - it >= localGraceMillis } == true)
            return RemoteAssistLinkLoss.LOCAL_NETWORK
        if (peerDisconnectedSince?.let { now - it >= peerGraceMillis } == true)
            return RemoteAssistLinkLoss.REMOTE_PEER
        return null
    }
}
