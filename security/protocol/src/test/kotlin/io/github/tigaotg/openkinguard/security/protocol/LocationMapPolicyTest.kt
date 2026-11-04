package io.github.tigaotg.openkinguard.security.protocol

import org.junit.Assert.*
import org.junit.Test

class LocationMapPolicyTest {
    @Test fun `WGS84 precision and order are retained for third party maps`() {
        assertEquals("34.3416000,108.9402000",
            LocationMapPolicy.coordinates(343_416_000, 1_089_402_000))
        assertEquals("-90.0000000,-180.0000000",
            LocationMapPolicy.coordinates(-900_000_000, -1_800_000_000))
    }

    @Test fun `reject out of range location before sharing to a third party`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationMapPolicy.coordinates(910_000_000, 1_089_402_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocationMapPolicy.coordinates(343_416_000, 1_810_000_000)
        }
    }

    @Test fun `historical coordinates are explicitly marked after five minutes`() {
        val now = 1_800_000_000_000L
        assertFalse(LocationMapPolicy.isHistorical(now - 299_000L, now))
        assertFalse(LocationMapPolicy.isHistorical(now - 300_000L, now))
        assertTrue(LocationMapPolicy.isHistorical(now - 300_001L, now))
        assertTrue(LocationMapPolicy.isHistorical(0L, now))
        assertTrue(LocationMapPolicy.isHistorical(now + 61_000L, now))
    }
}
