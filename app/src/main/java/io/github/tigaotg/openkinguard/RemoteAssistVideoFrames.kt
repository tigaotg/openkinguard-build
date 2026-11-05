package io.github.tigaotg.openkinguard

import io.github.tigaotg.openkinguard.security.protocol.RemoteAssistVideoWire
import java.security.MessageDigest

/** Only retains codec setup and newest dimensions; no recording or screenshot history. */
internal class RemoteAssistVideoFrames(private val clock: () -> Long = System::currentTimeMillis) {
    data class Unit(val width: Int, val height: Int, val presentationUs: Long,
        val flags: Int, val bytes: ByteArray, val receivedAt: Long)
    private var latestAt = 0L
    private var dimensions: Pair<Int, Int>? = null
    private var config: Unit? = null

    @Synchronized fun accept(packet: RemoteAssistVideoWire.Packet, bytes: ByteArray): Unit? {
        if (packet.length != bytes.size) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (digest != packet.sha256) return null
        val now = clock()
        val complete = Unit(packet.width, packet.height, packet.presentationUs,
            packet.flags, bytes, now)
        if (packet.flags and 2 != 0) config = complete
        else { latestAt = now; dimensions = packet.width to packet.height }
        return complete
    }

    @Synchronized fun fresh(): Boolean = latestAt != 0L && clock() - latestAt in 0..5_000
    @Synchronized fun size(): Pair<Int, Int>? = dimensions
    @Synchronized fun config(): Unit? = config
    @Synchronized fun clear() { latestAt = 0; dimensions = null; config = null }
}
