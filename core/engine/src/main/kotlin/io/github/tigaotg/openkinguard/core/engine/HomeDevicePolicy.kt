package io.github.tigaotg.openkinguard.core.engine

/** Device identity, not relation direction or mutable display nickname, owns a home card. */
data class HomeRolePresence(
    val fingerprint: String,
    val relationId: String,
    val role: String,
    val verifiedOnline: Boolean,
)

data class HomeDevice(
    val fingerprint: String,
    val relations: List<HomeRolePresence>,
    val online: Boolean,
) {
    val roles: Set<String> get() = relations.map { it.role }.toSet()
}

object HomeDevicePolicy {
    fun collapse(relations: List<HomeRolePresence>): List<HomeDevice> =
        relations.groupBy { it.fingerprint }.map { (fingerprint, entries) ->
            HomeDevice(fingerprint, entries.distinctBy { it.relationId },
                entries.any { it.verifiedOnline })
        }
}
