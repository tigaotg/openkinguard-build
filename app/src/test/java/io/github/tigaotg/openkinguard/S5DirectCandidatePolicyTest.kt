package io.github.tigaotg.openkinguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class S5DirectCandidatePolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun acceptsNumericFreshMappedIpv4() {
        val candidate = S5DirectCandidatePolicy.accept("203.0.113.18", 54321,
            now + 90_000L, now)
        assertEquals("203.0.113.18", candidate?.ip)
        assertEquals(54321, candidate?.port)
        assertTrue(S5DirectCandidatePolicy.fresh(requireNotNull(candidate), now + 20_000L))
    }

    @Test fun forbidsLocalMulticastAndHostnames() {
        assertNull(S5DirectCandidatePolicy.accept("127.0.0.1", 3478, now + 90_000, now))
        assertNull(S5DirectCandidatePolicy.accept("0.0.0.0", 3478, now + 90_000, now))
        assertNull(S5DirectCandidatePolicy.accept("224.0.0.1", 3478, now + 90_000, now))
        assertNull(S5DirectCandidatePolicy.accept("relay.example.com", 3478, now + 90_000, now))
    }

    @Test fun acceptsGlobalIpv6Candidates() {
        val candidate = S5DirectCandidatePolicy.accept(
            "2001:4860:4860::8888", 54321, now + 90_000L, now)
        assertEquals(54321, candidate?.port)
        assertTrue(candidate?.ip?.contains(":") == true)
    }

    @Test fun rejectsLinkLocalAndUniqueLocalIpv6Candidates() {
        assertNull(S5DirectCandidatePolicy.accept("fe80::1", 54321, now + 90_000L, now))
        assertNull(S5DirectCandidatePolicy.accept("fc00::1", 54321, now + 90_000L, now))
    }

    @Test fun rejectsInvalidPortsAndExpiredCandidates() {
        assertNull(S5DirectCandidatePolicy.accept("203.0.113.18", 0, now + 90_000, now))
        assertNull(S5DirectCandidatePolicy.accept("203.0.113.18", 65536,
            now + 90_000, now))
        assertNull(S5DirectCandidatePolicy.accept("203.0.113.18", 54321, now - 1, now))
        assertNull(S5DirectCandidatePolicy.accept("203.0.113.18", 54321,
            now + 150_000, now))
        val valid = requireNotNull(S5DirectCandidatePolicy.accept(
            "203.0.113.18", 54321, now + 60_000, now))
        assertFalse(S5DirectCandidatePolicy.fresh(valid, now + 60_000))
    }
}
