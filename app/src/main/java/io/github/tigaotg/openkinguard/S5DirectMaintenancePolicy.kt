package io.github.tigaotg.openkinguard

/**
 * Keep NAT candidates fresh for trusted peers, but avoid needless DNS/STUN work
 * when no paired device can consume them. Socket close() interrupts receive(),
 * so an infinite receive timeout is safe and avoids idle polling wakeups.
 */
internal data class S5DirectMaintenancePlan(
    val probeStun: Boolean,
    val refreshIntervalSeconds: Long
)

internal object S5DirectMaintenancePolicy {
    const val ACTIVE_PEER_REFRESH_SECONDS = 60L
    const val IDLE_REFRESH_SECONDS = 300L
    const val BLOCKING_RECEIVE_TIMEOUT_MILLIS = 0

    fun plan(activePeerCount: Int): S5DirectMaintenancePlan {
        require(activePeerCount >= 0)
        return if (activePeerCount > 0) {
            S5DirectMaintenancePlan(
                probeStun = true,
                refreshIntervalSeconds = ACTIVE_PEER_REFRESH_SECONDS
            )
        } else {
            S5DirectMaintenancePlan(
                probeStun = false,
                refreshIntervalSeconds = IDLE_REFRESH_SECONDS
            )
        }
    }
}
