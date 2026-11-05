package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RemoteAssistStreamWireTest {
    private val relation = UUID.randomUUID().toString()
    private val session = UUID.randomUUID().toString()
    private val frameId = UUID.randomUUID().toString()
    private val messageId = UUID.randomUUID().toString()

    private fun signed(kind: String, bytes: ByteArray, event: String = session) =
        MessageEnvelope(1, MessageId(messageId), EventId(event), DeviceId("sender"),
            UserId("subject"), DeviceId("recipient"), 1000L, 5000L, 0L, kind, bytes)

    @Test fun `image packet splits and retains session scope`() {
        val fragment = ByteArray(2000) { (it % 255).toByte() }
        val body = RemoteAssistStreamWire.decode(signed(RemoteAssistStreamWire.FRAME,
            RemoteAssistStreamWire.frame(relation, session, frameId, 2, 4,
                420, 780, fragment)))
        assertArrayEquals(fragment, body.jpegPart)
        assertEquals(frameId, body.frameId)
        assertEquals(2, body.part)
        assertEquals(4, body.total)
        assertEquals(420, body.width)
    }

    @Test fun `gestures and signed acknowledgements must match this session`() {
        val tap = RemoteAssistStreamWire.decode(signed(RemoteAssistStreamWire.TAP,
            RemoteAssistStreamWire.tap(relation, session, 7200, 2500)))
        assertEquals(7200, tap.x)
        assertEquals(2500, tap.y)
        val swipe = RemoteAssistStreamWire.decode(signed(RemoteAssistStreamWire.SWIPE,
            RemoteAssistStreamWire.swipe(relation, session, 5000, 9000, 5000, 1000)))
        assertEquals(9000, swipe.y)
        assertEquals(1000, swipe.y2)
        val ack = RemoteAssistStreamWire.decode(signed(RemoteAssistStreamWire.ACK,
            RemoteAssistStreamWire.ack(relation, session, messageId)))
        assertEquals(messageId, ack.respondsTo)
        assertTrue(runCatching { RemoteAssistStreamWire.decode(signed(
            RemoteAssistStreamWire.TAP, RemoteAssistStreamWire.tap(relation, session, 1, 1),
            UUID.randomUUID().toString())) }.isFailure)
    }

    @Test fun `out of bounds coordinates and fragment sizes reject before sending`() {
        assertTrue(runCatching { RemoteAssistStreamWire.tap(relation, session, 10001, 2) }
            .isFailure)
        assertTrue(runCatching { RemoteAssistStreamWire.frame(relation, session, frameId,
            0, 1, 400, 600, ByteArray(4000)) }.isFailure)
    }

    @Test fun `diagnostics are utf8 signed session scoped and strictly capped`() {
        val report = "编码=H264; 帧率=12.4; 状态=等待关键帧"
        val body = RemoteAssistStreamWire.decode(signed(RemoteAssistStreamWire.DIAGNOSTIC,
            RemoteAssistStreamWire.diagnostic(relation, session, report)))
        assertEquals(report, body.diagnosticText)
        assertTrue(runCatching { RemoteAssistStreamWire.decode(signed(
            RemoteAssistStreamWire.DIAGNOSTIC,
            RemoteAssistStreamWire.diagnostic(relation, session, report),
            UUID.randomUUID().toString())) }.isFailure)
        assertTrue(runCatching { RemoteAssistStreamWire.diagnostic(relation, session,
            "x".repeat(1101)) }.isFailure)
        assertTrue(runCatching { RemoteAssistStreamWire.decode(signed(
            RemoteAssistStreamWire.DIAGNOSTIC,
            RemoteAssistStreamWire.diagnostic(relation, session, report).also {
                it[it.lastIndex] = '*'.code.toByte()
            })) }.isFailure)
    }
}
