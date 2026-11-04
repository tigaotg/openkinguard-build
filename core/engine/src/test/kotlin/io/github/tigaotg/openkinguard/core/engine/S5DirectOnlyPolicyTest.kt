package io.github.tigaotg.openkinguard.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S5DirectOnlyPolicyTest {
    @Test fun `public signal type whitelist excludes application payloads`() {
        assertEquals(S5SignalKind.CANDIDATES,
            S5DirectOnlyPolicy.parseSignalKind("CANDIDATES"))
        listOf("SOS", "GPS", "LOCATION", "CHECKIN", "PACKET", "MEDICAL", "packet")
            .forEach { assertNull(S5DirectOnlyPolicy.parseSignalKind(it)) }
    }

    @Test fun `disconnected devices retry immediately then every ten seconds`() {
        val p = S5DirectOnlyPolicy
        assertTrue(p.shouldAttemptRendezvous(S5DirectState.DISCONNECTED, null, 20_000))
        assertFalse(p.shouldAttemptRendezvous(S5DirectState.NEGOTIATING, 20_000, 29_999))
        assertTrue(p.shouldAttemptRendezvous(S5DirectState.NEGOTIATING, 20_000, 30_000))
        assertFalse(p.shouldAttemptRendezvous(S5DirectState.DIRECT_READY, null, 50_000))
    }

    @Test fun `optional five second timing uses monotonic milliseconds`() {
        val p = S5DirectOnlyPolicy
        assertFalse(p.shouldAttemptRendezvous(S5DirectState.DISCONNECTED,
            100_000, 104_999, 5))
        assertTrue(p.shouldAttemptRendezvous(S5DirectState.DISCONNECTED,
            100_000, 105_000, 5))
        assertTrue(p.shouldAttemptRendezvous(S5DirectState.UNREACHABLE,
            100_000, 999, 5))
    }

    @Test fun `only verified direct identity and signed peer ACK indicate readiness`() {
        val p = S5DirectOnlyPolicy
        assertFalse(p.directReady(false, true, true))
        assertFalse(p.directReady(true, false, true))
        assertFalse(p.directReady(true, true, false))
        assertTrue(p.directReady(true, true, true))
    }

    @Test
    fun `rendezvous envelope cannot live for more than thirty seconds`() {
        assertFailsWith<IllegalArgumentException> {
            S5RendezvousSignal(S5SignalKind.PRESENCE, "a".repeat(32),
                "b".repeat(64), "c".repeat(64), 100_000, 131_000, 0)
        }
    }

    @Test
    fun `unpaired self recipient cannot be a rendezvous target`() {
        assertFailsWith<IllegalArgumentException> {
            S5RendezvousSignal(S5SignalKind.CANDIDATES, "a".repeat(32),
                "b".repeat(64), "b".repeat(64), 100_000, 120_000, 0)
        }
    }
}
