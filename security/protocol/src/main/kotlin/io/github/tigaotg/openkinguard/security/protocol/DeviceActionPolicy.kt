package io.github.tigaotg.openkinguard.security.protocol

/** Presentation and routing rules independent of Android and network discovery. */
object DeviceActionPolicy {
    /** A new *unverified* broadcast may update a discovery label, never the trusted identity. */
    fun discoveryLabel(advertised: String, verified: String, localAlias: String,
                       serviceName: String): String = advertised.ifBlank { verified }
        .ifBlank { localAlias }.ifBlank { serviceName }

    /** An empty target means the explicit broadcast action; otherwise never fan out. */
    fun canDeliverCheckin(targetRelationId: String, recipientRelationId: String): Boolean =
        targetRelationId.isEmpty() || targetRelationId == recipientRelationId
}
