package io.github.tigaotg.openkinguard

/** Local per-relation opt-out, distinct from one-time Android screen-capture consent. */
internal object RemoteAssistPermissionPolicy {
    fun allowed(blockedRelations: Set<String>, relationId: String): Boolean =
        relationId !in blockedRelations

    fun withPermission(blockedRelations: Set<String>, relationId: String,
                       allowed: Boolean): Set<String> =
        if (allowed) blockedRelations - relationId else blockedRelations + relationId
}
