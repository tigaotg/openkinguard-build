package io.github.tigaotg.openkinguard

import io.github.tigaotg.openkinguard.security.protocol.RemoteAssistStreamWire
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RemoteAssistFramesTest {
    @Test fun `preview exists only after all fragments and vanishes on revoke`() {
        var now = 1_000L
        val cache = RemoteAssistFrames { now }
        val id = UUID.randomUUID().toString()
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte()) + ByteArray(100) { 42 }
        val one = RemoteAssistStreamWire.Packet("r", "s", id, 0, 2,
            320, 500, jpeg.copyOfRange(0, 50))
        val two = one.copy(part = 1, jpegPart = jpeg.copyOfRange(50, jpeg.size))
        assertFalse(cache.accept(two))
        assertNull(cache.latest())
        assertTrue(cache.accept(one))
        assertArrayEquals(jpeg, cache.latest()?.jpeg)
        now += 20_001
        assertNull(cache.latest())
        cache.clear()
        assertNull(cache.latest())
    }
}
