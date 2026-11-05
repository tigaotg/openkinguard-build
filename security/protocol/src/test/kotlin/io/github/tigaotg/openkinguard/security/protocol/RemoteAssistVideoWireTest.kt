package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RemoteAssistVideoWireTest {
    private val relation = UUID.randomUUID().toString()
    private val session = UUID.randomUUID().toString()
    private fun message(kind: String, payload: ByteArray) = MessageEnvelope(
        1, MessageId(UUID.randomUUID().toString()), EventId(session), DeviceId("src"),
        UserId("subject"), DeviceId("dst"), 100, 10000, 1, kind, payload)

    @Test fun `signed unit header binds video length and digest`() {
        val hash = "a".repeat(64)
        val wire = RemoteAssistVideoWire.unit(relation, session, 42,
            720, 1280, 151_000, 1, 18_000, hash)
        val decoded = RemoteAssistVideoWire.decode(message(RemoteAssistVideoWire.UNIT, wire))
        assertEquals(18_000, decoded.length)
        assertEquals(hash, decoded.sha256)
        assertEquals(720, decoded.width)
        assertEquals(1280, decoded.height)
        assertEquals(151_000, decoded.presentationUs)
        assertEquals(1, decoded.flags)
    }

    @Test fun `session must match signed envelope and access unit must stay bounded`() {
        val wire = RemoteAssistVideoWire.open(relation, session)
        assertEquals(session, RemoteAssistVideoWire.decode(message(RemoteAssistVideoWire.OPEN,
            wire)).sessionId)
        assertTrue(runCatching { RemoteAssistVideoWire.unit(relation, session, 1,
            720, 1280, 0, 0, RemoteAssistVideoWire.MAX_UNIT_BYTES + 1,
            "a".repeat(64)) }.isFailure)
        val other = UUID.randomUUID().toString()
        assertTrue(runCatching { RemoteAssistVideoWire.decode(message(
            RemoteAssistVideoWire.OPEN, RemoteAssistVideoWire.open(relation, other))) }.isFailure)
    }
}
