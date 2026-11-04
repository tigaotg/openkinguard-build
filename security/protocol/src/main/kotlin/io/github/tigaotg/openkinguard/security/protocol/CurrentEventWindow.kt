package io.github.tigaotg.openkinguard.security.protocol

/** A projection only: old events stay in encrypted history instead of being deleted. */
data class EventSlot(
    val eventId: String,
    val originFingerprint: String,
    val observedAt: Long,
    val supersededBy: String = "",
    val closed: Boolean = false,
)

object CurrentEventWindow {
    /** At most one non-closed event per originating device is presented as current. */
    fun currentIds(records: List<EventSlot>): Set<String> {
        val chosen = mutableMapOf<String, EventSlot>()
        records.forEach { event ->
            if (!event.closed && event.supersededBy.isBlank()) {
                val old = chosen[event.originFingerprint]
                if (old == null || event.observedAt >= old.observedAt) {
                    chosen[event.originFingerprint] = event
                }
            }
        }
        return chosen.values.mapTo(mutableSetOf()) { it.eventId }
    }

    /** The signed origin timestamp prevents a delayed old packet reviving a replaced event. */
    fun arrivesAfter(newOriginTime: Long, oldOriginTime: Long): Boolean =
        oldOriginTime <= 0L || newOriginTime > oldOriginTime
}
