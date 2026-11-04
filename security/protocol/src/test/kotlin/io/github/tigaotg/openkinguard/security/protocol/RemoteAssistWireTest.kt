package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RemoteAssistWireTest {
    private val relation = UUID.randomUUID().toString()
    private val session = UUID.randomUUID().toString()
    private val messageId = UUID.randomUUID().toString()

    private fun frame(kind: String, data: ByteArray, eventId: String = session) =
        MessageEnvelope(1, MessageId(messageId), EventId(eventId), DeviceId("sender"),
            UserId("subject"), DeviceId("recipient"), 1000L, 5000L, 0L, kind, data)

    @Test fun `all control messages use bounded signed envelope`() {
        val samples = listOf(
            RemoteAssistWire.REQUEST to RemoteAssistWire.request(relation, session),
            RemoteAssistWire.ACCEPT to RemoteAssistWire.accept(relation, session),
            RemoteAssistWire.DECLINE to RemoteAssistWire.decline(relation, session),
            RemoteAssistWire.STOP to RemoteAssistWire.stop(relation, session),
            RemoteAssistWire.RECEIPT to RemoteAssistWire.receipt(relation, session, messageId))
        samples.forEach { (kind, bytes) ->
            val body = RemoteAssistWire.body(frame(kind, bytes))
            assertEquals(relation, body.relationId)
            assertEquals(session, body.sessionId)
            assertTrue(bytes.size <= 150)
            assertEquals(if (kind == RemoteAssistWire.RECEIPT) messageId else null,
                body.respondingTo)
            assertEquals(relation, LanTestWire.body(frame(kind, bytes)).relationId)
        }
    }

    @Test fun `wrong session or packet kind cannot be reinterpreted`() {
        val bytes = RemoteAssistWire.accept(relation, session)
        assertTrue(runCatching { RemoteAssistWire.body(frame(RemoteAssistWire.REQUEST, bytes)) }
            .isFailure)
        assertTrue(runCatching { RemoteAssistWire.body(frame(RemoteAssistWire.ACCEPT,
            bytes, UUID.randomUUID().toString())) }.isFailure)
    }

    @Test fun `receipt always binds original message ID`() {
        val ack = frame(RemoteAssistWire.RECEIPT,
            RemoteAssistWire.receipt(relation, session, messageId))
        assertEquals(messageId, RemoteAssistWire.body(ack).respondingTo)
    }
}
