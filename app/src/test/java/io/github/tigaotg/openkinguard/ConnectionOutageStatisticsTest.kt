package io.github.tigaotg.openkinguard

import org.junit.Assert.*
import org.junit.Test

class ConnectionOutageStatisticsTest {
    private val t = 1_800_000_000_000L
    private fun event(time: Long, path: String, old: String, new: String,
                      kind: String = "change") =
        ConnectionHistoryPoint(time, kind, path, old, new)

    @Test fun countsVerifiedOutagesAndRecoveryDurationsOnly() {
        val result = ConnectionOutageStatistics.analyze(listOf(
            event(t, "relay/1/socket", "connected", "disconnected"),
            event(t + 21_000L, "relay/1/socket", "disconnected", "connected"),
            event(t + 60_000L, "relay/1/socket", "connected", "disconnected"),
            event(t + 65_000L, "relay/1/socket", "disconnected", "connected"),
        ), t + 70_000L)
        val a = result.routes.single()
        assertEquals(2, a.disconnects)
        assertEquals(2, a.recoveries)
        assertEquals(26_000L, a.verifiedOutageMs)
        assertEquals(21_000L, a.longestVerifiedOutageMs)
        assertEquals(0, a.uncertainSpans)
    }

    @Test fun initialOfflineIsNotMistakenForDisconnection() {
        val r = ConnectionOutageStatistics.analyze(listOf(
            event(t, "peer/abcdef/lan", "unknown", "unverified", "initial")
        ), t + 1000)
        assertTrue(r.routes.isEmpty())
    }

    @Test fun firstEverConnectionIsNotRecoveryFromAnOutage() {
        val r = ConnectionOutageStatistics.analyze(listOf(
            event(t, "relay/1/socket", "unknown", "disconnected", "initial"),
            event(t + 10_000L, "relay/1/socket", "disconnected", "connected")
        ), t + 20_000L)
        assertTrue(r.routes.isEmpty())
    }

    @Test fun monitorGapDoesNotCountAsContinuousConfirmedOutage() {
        val r = ConnectionOutageStatistics.analyze(listOf(
            event(t, "relay/1/auth", "verified", "unverified"),
            event(t + 350_000L, "monitor/sampling", "unknown", "unknown", "gap"),
            event(t + 350_000L, "relay/1/auth", "unverified", "verified")
        ), t + 360_000L)
        val entry = r.routes.single()
        assertEquals(1, r.samplingGaps)
        assertEquals(1, entry.uncertainSpans)
        assertEquals(0L, entry.verifiedOutageMs)
    }

    @Test fun countsFailingRelayAttemptsWithoutExposingServerReason() {
        val r = ConnectionOutageStatistics.analyze(listOf(
            event(t, "relay/1/phase", "connecting", "backoff"),
            event(t + 1000, "relay/1/failure", "none", "dns")
        ), t + 2000L)
        val v = r.routes.single()
        assertEquals(1, v.failureEvents)
        assertEquals("dns", v.lastFailureCategory)
        assertEquals(0, v.disconnects)
    }

    @Test fun identifiesNetworkOutageButNotWifiToCellularHandover() {
        val r = ConnectionOutageStatistics.analyze(listOf(
            event(t, "network/type", "wifi", "cellular"),
            event(t + 1000, "network/type", "cellular", "offline"),
            event(t + 9000, "network/type", "offline", "wifi")
        ), t + 12000L)
        assertEquals(1, r.routes.single().disconnects)
        assertEquals(8000L, r.routes.single().verifiedOutageMs)
    }

    @Test fun stillDisconnectedDoesNotClaimVerifiedDuration() {
        val r = ConnectionOutageStatistics.analyze(listOf(
            event(t, "peer/aaaaaa/udp", "verified", "unverified")
        ), t + 24000)
        assertEquals(t, r.routes.single().currentlyDisconnectedSinceMs)
        assertEquals(0L, r.routes.single().verifiedOutageMs)
    }
}
