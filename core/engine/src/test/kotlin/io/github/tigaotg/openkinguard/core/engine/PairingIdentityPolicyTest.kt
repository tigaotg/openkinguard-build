package io.github.tigaotg.openkinguard.core.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PairingIdentityPolicyTest {
    private fun binding(id: String, fingerprint: String, role: String,
                        active: Boolean = true) =
        DirectedPeerBinding(id, fingerprint, role, active)

    @Test fun `same fingerprint and direction cannot be bound twice`() {
        val existing = listOf(binding("old", "device-A", "SUBJECT"))
        assertEquals("old", PairingIdentityPolicy.conflictingRelation(
            existing, "device-A", "SUBJECT"))
    }

    @Test fun `reverse direction remains independently allowed`() {
        val existing = listOf(binding("old", "device-A", "SUBJECT"))
        assertNull(PairingIdentityPolicy.conflictingRelation(existing, "device-A", "HELPER"))
    }

    @Test fun `another device is unaffected`() {
        val existing = listOf(binding("old", "device-A", "SUBJECT"))
        assertNull(PairingIdentityPolicy.conflictingRelation(existing, "device-B", "SUBJECT"))
    }

    @Test fun `revoked historical records do not block pairing`() {
        val existing = listOf(binding("old", "device-A", "SUBJECT", false))
        assertNull(PairingIdentityPolicy.conflictingRelation(existing, "device-A", "SUBJECT"))
    }

    @Test fun `only same relation can be exempted during idempotent replay`() {
        val existing = listOf(binding("old", "device-A", "SUBJECT"))
        assertNull(PairingIdentityPolicy.conflictingRelation(existing,
            "device-A", "SUBJECT", "old"))
        assertEquals("old", PairingIdentityPolicy.conflictingRelation(existing,
            "device-A", "SUBJECT", "new"))
    }

    @Test fun `legacy duplicate grouping preserves both records for user review`() {
        val all = listOf(binding("first", "device-A", "HELPER"),
            binding("second", "device-A", "HELPER"),
            binding("reverse", "device-A", "SUBJECT"),
            binding("old-revoked", "device-B", "HELPER", false))
        assertEquals(listOf("first", "second"), PairingIdentityPolicy.duplicateGroups(all)
            .single().map { it.relationId })
    }
}
