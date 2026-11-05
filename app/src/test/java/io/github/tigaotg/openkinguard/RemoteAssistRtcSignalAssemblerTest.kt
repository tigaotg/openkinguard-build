package io.github.tigaotg.openkinguard

import io.github.tigaotg.openkinguard.security.protocol.RemoteAssistStreamWire
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RemoteAssistRtcSignalAssemblerTest {
    @Test fun chunksCompleteOnlyAfterAllPartsAndAreSessionScoped() {
        var now = 1000L
        val parts = RemoteAssistRtcSignalAssembler { now }
        val relation = UUID.randomUUID().toString()
        val session = UUID.randomUUID().toString()
        val id = UUID.randomUUID().toString()
        val data = "v=0\r\na=ice-ufrag:rtc123\r\n".repeat(80).toByteArray()
        val chunks = data.asList().chunked(900).mapIndexed { index, bytes ->
            RemoteAssistStreamWire.Packet(relation, session, rtcType = "offer",
                rtcId = id, rtcPart = index, rtcTotal = (data.size + 899) / 900,
                rtcBytes = bytes.toByteArray())
        }
        var value: RemoteAssistRtcSignalAssembler.Signal? = null
        for (packet in chunks.asReversed()) {
            val result = parts.accept(packet)
            if (result != null) value = result
        }
        assertEquals("offer", value?.type)
        assertEquals(String(data), value?.text)
        assertNull(parts.accept(chunks.first()))
        parts.clear()
        now += 31_000
        assertNull(parts.accept(chunks.last()))
    }
}
