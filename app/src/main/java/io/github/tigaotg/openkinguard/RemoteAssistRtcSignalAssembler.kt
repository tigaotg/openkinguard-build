package io.github.tigaotg.openkinguard

import io.github.tigaotg.openkinguard.security.protocol.RemoteAssistStreamWire
import java.io.ByteArrayOutputStream

/** Bounded, short-lived reassembly of signed WebRTC SDP and ICE, never screen pixels. */
internal class RemoteAssistRtcSignalAssembler(private val now: () -> Long = System::currentTimeMillis) {
    data class Signal(val type: String, val text: String)
    private class Pending(val type: String, val startedAt: Long, val parts: Array<ByteArray?>)
    private val pending = LinkedHashMap<String, Pending>()

    @Synchronized fun accept(packet: RemoteAssistStreamWire.Packet): Signal? {
        require(packet.rtcType in setOf("offer", "answer", "ice"))
        require(packet.rtcTotal in 1..40 && packet.rtcPart in 0 until packet.rtcTotal)
        require(packet.rtcBytes.size in 1..950)
        val time = now()
        pending.entries.removeAll { time - it.value.startedAt > 30_000 }
        if (pending.size >= 12 && !pending.containsKey(packet.rtcId))
            pending.remove(pending.keys.first())
        val item = pending.getOrPut(packet.rtcId) {
            Pending(packet.rtcType, time, arrayOfNulls(packet.rtcTotal))
        }
        if (item.type != packet.rtcType || item.parts.size != packet.rtcTotal) {
            pending.remove(packet.rtcId)
            return null
        }
        item.parts[packet.rtcPart] = packet.rtcBytes
        if (item.parts.any { it == null }) return null
        pending.remove(packet.rtcId)
        val data = ByteArrayOutputStream()
        item.parts.forEach { data.write(requireNotNull(it)) }
        val bytes = data.toByteArray()
        require(bytes.size in 1..38_000)
        return Signal(item.type,
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString())
    }

    @Synchronized fun clear() { pending.clear() }
}
