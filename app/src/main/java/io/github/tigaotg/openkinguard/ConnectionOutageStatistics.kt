package io.github.tigaotg.openkinguard

/** Platform-neutral and privacy-safe summaries. A gap is UNKNOWN, never a confirmed outage. */
internal data class ConnectionHistoryPoint(
    val at: Long, val kind: String, val path: String,
    val before: String, val after: String
)

internal data class ConnectionRouteSummary(
    val path: String,
    val disconnects: Int,
    val recoveries: Int,
    val verifiedOutageMs: Long,
    val longestVerifiedOutageMs: Long,
    val uncertainSpans: Int,
    val currentlyDisconnectedSinceMs: Long?,
    val failureEvents: Int,
    val lastFailureCategory: String?
)
internal data class ConnectionOutageReport(
    val routes: List<ConnectionRouteSummary>,
    val samplingGaps: Int
)

internal object ConnectionOutageStatistics {
    private data class MutableRoute(
        var disconnects: Int = 0,
        var recoveries: Int = 0,
        var total: Long = 0L,
        var longest: Long = 0L,
        var uncertain: Int = 0,
        var downSince: Long? = null,
        var spanUncertain: Boolean = false,
        var failures: Int = 0,
        var lastFailure: String? = null
    )

    private fun isMonitored(path: String): Boolean =
        path == "network/type" || path == "nostr/socket" || path == "udp/socket" ||
            (path.startsWith("relay/") && (path.endsWith("/socket") || path.endsWith("/auth"))) ||
            (path.startsWith("peer/") &&
                (path.endsWith("/lan") || path.endsWith("/relay") || path.endsWith("/udp")))

    private fun healthy(path: String, value: String): Boolean = when {
        path == "network/type" -> value !in setOf("offline", "unknown", "removed")
        path == "nostr/socket" || path.endsWith("/socket") -> value == "connected" ||
            (path == "udp/socket" && value == "active")
        path.endsWith("/auth") || path.startsWith("peer/") -> value == "verified"
        else -> false
    }

    fun analyze(points: List<ConnectionHistoryPoint>, now: Long): ConnectionOutageReport {
        val state = linkedMapOf<String, MutableRoute>()
        var gaps = 0
        // A gap at the same timestamp takes precedence over a recovery; otherwise
        // the preceding offline interval could be incorrectly classified as verified.
        val ordered = points.asSequence().filter { it.at in
            (now - ConnectionHistoryPolicy.RETENTION_MS)..now }
            .sortedWith(compareBy<ConnectionHistoryPoint> { it.at }
                .thenBy { if (it.kind == "gap") 0 else 1 })
        for (event in ordered) {
            if (event.kind == "gap") {
                gaps++
                state.values.forEach { route ->
                    if (route.downSince != null) route.spanUncertain = true
                }
                continue
            }
            if (event.kind != "change") continue
            if (isMonitored(event.path)) {
                val before = healthy(event.path, event.before)
                val after = healthy(event.path, event.after)
                if (before == after) continue
                val route = state.getOrPut(event.path) { MutableRoute() }
                if (before) {
                    route.disconnects++
                    route.downSince = event.at
                    route.spanUncertain = false
                } else {
                    route.downSince?.let { start ->
                        route.recoveries++
                        if (route.spanUncertain) {
                            route.uncertain++
                        } else {
                            val elapsed = (event.at - start).coerceAtLeast(0L)
                            route.total += elapsed
                            route.longest = maxOf(route.longest, elapsed)
                        }
                    }
                    route.downSince = null
                    route.spanUncertain = false
                }
            }
            // Count retry/failure phases separately from genuine lost sockets.
            if (event.path.startsWith("relay/") && event.path.endsWith("/phase") &&
                event.after in setOf("backoff", "denied")) {
                state.getOrPut(event.path.removeSuffix("/phase") + "/socket") { MutableRoute() }
                    .failures++
            }
            if (event.path.startsWith("relay/") && event.path.endsWith("/failure") &&
                event.after !in setOf("none", "unknown", "removed")) {
                val route = state.getOrPut(event.path.removeSuffix("/failure") + "/socket") {
                    MutableRoute()
                }
                route.lastFailure = event.after
            }
        }
        return ConnectionOutageReport(state.map { (key, route) ->
            ConnectionRouteSummary(key, route.disconnects, route.recoveries,
                route.total, route.longest, route.uncertain,
                route.downSince, route.failures, route.lastFailure)
        }.filter { it.disconnects > 0 || it.recoveries > 0 || it.failureEvents > 0 }
            .sortedWith(compareByDescending<ConnectionRouteSummary> { it.disconnects }
                .thenBy { it.path }), gaps)
    }
}
