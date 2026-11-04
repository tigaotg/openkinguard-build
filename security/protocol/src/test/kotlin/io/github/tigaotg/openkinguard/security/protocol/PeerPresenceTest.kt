package io.github.tigaotg.openkinguard.security.protocol

import org.junit.Assert.*
import org.junit.Test

class PeerPresenceTest {
    private val now = 1_800_000_000_000L

    @Test fun `never verified does not trigger false lost connection`() {
        assertEquals(PeerPresenceLevel.NEVER_SEEN, PeerPresence.level(null, now))
        assertEquals(0, PeerPresence.warningStage(0L, now))
    }

    @Test fun `three and ten minute boundaries trigger independent stages`() {
        assertEquals(PeerPresenceLevel.RECENT, PeerPresence.level(now - 59_000L, now))
        assertEquals(PeerPresenceLevel.WAITING, PeerPresence.level(now - 120_000L, now))
        assertEquals(0, PeerPresence.warningStage(now - 179_999L, now))
        assertEquals(1, PeerPresence.warningStage(now - 180_000L, now))
        assertEquals(1, PeerPresence.warningStage(now - 599_999L, now))
        assertEquals(2, PeerPresence.warningStage(now - 600_000L, now))
    }

    @Test fun `freshly verified exchange recovers and clock skew does not alarm`() {
        assertEquals(0, PeerPresence.warningStage(now, now))
        assertEquals(PeerPresenceLevel.RECENT, PeerPresence.level(now + 10_000L, now))
    }

    @Test fun `custom loss timers do not change signed online recency`() {
        val warning = 5
        val critical = 15
        assertEquals(PeerPresenceLevel.RECENT,
            PeerPresence.level(now - 89_000, now, warning, critical))
        assertEquals(PeerPresenceLevel.WAITING,
            PeerPresence.level(now - 120_000, now, warning, critical))
        assertEquals(0, PeerPresence.warningStage(now - 299_999, now, warning, critical))
        assertEquals(1, PeerPresence.warningStage(now - 300_000, now, warning, critical))
        assertEquals(1, PeerPresence.warningStage(now - 899_999, now, warning, critical))
        assertEquals(2, PeerPresence.warningStage(now - 900_000, now, warning, critical))
    }

    @Test fun `invalid or inverted thresholds cannot silently suppress warnings`() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerPresence.warningStage(now - 600_000, now, 10, 5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerPresence.level(now, now, 1, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerPresence.level(now, now, 30, 61)
        }
    }
}
