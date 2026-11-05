package io.github.tigaotg.openkinguard

import io.github.tigaotg.openkinguard.security.protocol.RemoteAssistVideoWire
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

class RemoteAssistVideoFramesTest {
    @Test fun `signed header checksum prevents corrupted screen media`() {
        var now = 1000L
        val frames = RemoteAssistVideoFrames { now }
        val bytes = ByteArray(12500) { it.toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        val relation = UUID.randomUUID().toString()
        val session = UUID.randomUUID().toString()
        val header = RemoteAssistVideoWire.unit(relation, session, 1, 720, 1280,
            250_000, 1, bytes.size, hash)
        val message = io.github.tigaotg.openkinguard.core.model.MessageEnvelope(
            1, io.github.tigaotg.openkinguard.core.model.MessageId(UUID.randomUUID().toString()),
            io.github.tigaotg.openkinguard.core.model.EventId(session),
            io.github.tigaotg.openkinguard.core.model.DeviceId("src"),
            io.github.tigaotg.openkinguard.core.model.UserId("subject"),
            io.github.tigaotg.openkinguard.core.model.DeviceId("dst"),
            100, 10000, 1, RemoteAssistVideoWire.UNIT, header)
        val packet = RemoteAssistVideoWire.decode(message)
        assertNull(frames.accept(packet, bytes.copyOf().apply { this[4] = 99 }))
        assertFalse(frames.fresh())
        assertNotNull(frames.accept(packet, bytes))
        assertTrue(frames.fresh())
        assertEquals(720 to 1280, frames.size())
        now += 5100
        assertFalse(frames.fresh())
    }
}
