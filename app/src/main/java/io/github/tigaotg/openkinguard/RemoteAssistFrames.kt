package io.github.tigaotg.openkinguard

import io.github.tigaotg.openkinguard.security.protocol.RemoteAssistStreamWire
import java.io.ByteArrayOutputStream

/** One in-memory preview. No recording, disk writes or stored screenshot history. */
internal class RemoteAssistFrames(private val clock: () -> Long = System::currentTimeMillis) {
    data class Preview(val jpeg: ByteArray, val width: Int, val height: Int,
                       val receivedAt: Long)
    private data class Pending(val id: String, val createdAt: Long, val width: Int,
        val height: Int, val parts: Array<ByteArray?>)
    private var pending: Pending? = null
    private var latest: Preview? = null
    private val completed = RemoteAssistTrafficMeter()

    @Synchronized fun diagnostics(): String = "JPEG完整画面: ${completed.summary()}\n" +
        "最后尺寸: ${latest?.let { "${it.width}x${it.height}" } ?: "尚未收到"}"

    @Synchronized fun accept(packet: RemoteAssistStreamWire.Packet): Boolean {
        val id = packet.frameId ?: return false
        val now = clock()
        if (pending?.let { it.createdAt + 12_000 < now } == true) pending = null
        if (pending == null || pending?.id != id) {
            pending = Pending(id, now, packet.width, packet.height,
                arrayOfNulls(packet.total))
        }
        val current = pending ?: return false
        if (current.id != id || current.width != packet.width ||
            current.height != packet.height || current.parts.size != packet.total ||
            packet.part !in current.parts.indices) return false
        if (current.parts[packet.part] == null) current.parts[packet.part] = packet.jpegPart
        if (current.parts.any { it == null }) return false
        val joined = ByteArrayOutputStream()
        current.parts.forEach { joined.write(requireNotNull(it)) }
        val data = joined.toByteArray()
        pending = null
        if (data.size !in 100..80_000 || data[0] != 0xff.toByte() ||
            data[1] != 0xd8.toByte()) return false
        latest = Preview(data, current.width, current.height, now)
        completed.record(data.size)
        return true
    }

    @Synchronized fun latest(): Preview? = latest?.takeIf {
        clock() - it.receivedAt in 0..20_000L
    }

    @Synchronized fun clear() {
        latest = null; pending = null
        completed.reset()
    }
}
