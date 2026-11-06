package io.github.tigaotg.openkinguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class FamilyRelayDiscoveryTest {
    @Test fun oneBrokenNodeCannotHideHealthyNode() {
        val found = FamilyRelayDiscovery.collect(listOf(
            0 to { throw IOException("offline") },
            1 to { listOf("a".repeat(64)) },
        ))
        assertEquals(listOf(FamilyRelayPeer("a".repeat(64), 1)), found)
    }

    @Test fun duplicateFingerprintsFromTwoNodesRemainOneDevice() {
        val same = "b".repeat(64)
        val found = FamilyRelayDiscovery.collect(listOf(
            0 to { listOf(same) }, 1 to { listOf(same) },
        ))
        assertEquals(listOf(FamilyRelayPeer(same, 0)), found)
    }

    @Test fun allNodesFailShowErrorRatherThanEmptyFamily() {
        assertThrows(IOException::class.java) {
            FamilyRelayDiscovery.collect(listOf(0 to { throw IOException("timeout") }))
        }
    }

    @Test fun partialFailureWithNoKnownPeersShowsUncertainty() {
        assertThrows(IOException::class.java) {
            FamilyRelayDiscovery.collect(listOf(
                0 to { emptyList() }, 1 to { throw IOException("timeout") },
            ))
        }
    }

    @Test fun noConfiguredFamilyNodesReturnEmpty() {
        assertEquals(emptyList<FamilyRelayPeer>(), FamilyRelayDiscovery.collect(emptyList()))
    }
}
