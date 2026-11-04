package io.github.tigaotg.openkinguard.core.engine

/** A trusted device can occupy each directed role only once; other devices are independent. */
data class DirectedPeerBinding(
    val relationId: String,
    val fingerprint: String,
    val role: String,
    val active: Boolean,
)

object PairingIdentityPolicy {
    fun conflictingRelation(
        bindings: List<DirectedPeerBinding>, fingerprint: String, role: String,
        exceptRelation: String? = null,
    ): String? = bindings.firstOrNull {
        it.active && it.fingerprint == fingerprint && it.role == role &&
            it.relationId != exceptRelation
    }?.relationId

    /** Only names conflicting roles; it does not revoke, rewrite or hide any trusted keys. */
    fun duplicateGroups(bindings: List<DirectedPeerBinding>): List<List<DirectedPeerBinding>> =
        bindings.filter { it.active }.groupBy { it.fingerprint to it.role }
            .values.filter { it.size > 1 }
}
