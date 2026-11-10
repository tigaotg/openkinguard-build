package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RemoteAssistStreamWireTest {
    private val relation = UUID.randomUUID().toString()
    private val session = UUID.randomUUID().toString()
    private val signalId = UUID.randomUUID().toString()
    private val messageId = UUID.randomUUID().toString()

    private fun envelope(kind: String, bytes: ByteArray, event: String = session) =
        MessageEnvelope(1, MessageId(messageId), EventId(event), DeviceId("sender"),
            UserId("subject"), DeviceId("recipient"), 1000L, 5000L, 0L, kind, bytes)

    @Test fun signedWebRtcOfferChunkKeepsBoundedSessionScope() {
        val chunk = ByteArray(900) { (it % 255).toByte() }
        val packet = RemoteAssistStreamWire.decode(envelope(RemoteAssistStreamWire.RTC,
            RemoteAssistStreamWire.rtc(relation, session, "offer", signalId, 2, 5, chunk)))
        assertArrayEquals(chunk, packet.rtcBytes)
        assertEquals(signalId, packet.rtcId)
        assertEquals(2, packet.rtcPart)
        assertEquals(5, packet.rtcTotal)
        assertEquals("offer", packet.rtcType)
        assertTrue(runCatching {
            RemoteAssistStreamWire.decode(envelope(RemoteAssistStreamWire.RTC,
                RemoteAssistStreamWire.rtc(relation, session, "offer", signalId, 0, 1,
                    byteArrayOf(2)), UUID.randomUUID().toString()))
        }.isFailure)
    }

    @Test fun gesturesAndAckRemainSessionBound() {
        val tap = RemoteAssistStreamWire.decode(envelope(RemoteAssistStreamWire.TAP,
            RemoteAssistStreamWire.tap(relation, session, 7200, 2500)))
        assertEquals(7200, tap.x)
        assertEquals(2500, tap.y)
        val swipe = RemoteAssistStreamWire.decode(envelope(RemoteAssistStreamWire.SWIPE,
            RemoteAssistStreamWire.swipe(relation, session, 5000, 9000, 5000, 1000)))
        assertEquals(9000, swipe.y)
        assertEquals(1000, swipe.y2)
        val recent = RemoteAssistStreamWire.decode(envelope(RemoteAssistStreamWire.RECENTS,
            RemoteAssistStreamWire.action(relation, session, RemoteAssistStreamWire.RECENTS)))
        assertEquals(session, recent.sessionId)
        val ack = RemoteAssistStreamWire.decode(envelope(RemoteAssistStreamWire.ACK,
            RemoteAssistStreamWire.ack(relation, session, messageId)))
        assertEquals(messageId, ack.respondsTo)
    }

    @Test fun outOfBoundsGestureAndSignalRejected() {
        assertTrue(runCatching {
            RemoteAssistStreamWire.tap(relation, session, 10001, 2)
        }.isFailure)
        assertTrue(runCatching {
            RemoteAssistStreamWire.rtc(relation, session, "offer", signalId,
                0, 1, ByteArray(1200))
        }.isFailure)
        assertTrue(runCatching {
            RemoteAssistStreamWire.rtc(relation, session, "video", signalId,
                0, 1, byteArrayOf(1))
        }.isFailure)
        assertTrue(runCatching {
            RemoteAssistStreamWire.rtc(relation, session, "ice", signalId,
                40, 40, byteArrayOf(1))
        }.isFailure)
    }

    @Test fun utf8DiagnosticsAreStrictlyCapped() {
        val report = "WebRTC ICE已连接；实测FPS=24"
        val body = RemoteAssistStreamWire.decode(envelope(RemoteAssistStreamWire.DIAGNOSTIC,
            RemoteAssistStreamWire.diagnostic(relation, session, report)))
        assertEquals(report, body.diagnosticText)
        assertTrue(runCatching {
            RemoteAssistStreamWire.diagnostic(relation, session, "x".repeat(1101))
        }.isFailure)
        assertTrue(runCatching {
            RemoteAssistStreamWire.decode(envelope(RemoteAssistStreamWire.DIAGNOSTIC,
                RemoteAssistStreamWire.diagnostic(relation, session, report).also {
                    it[it.lastIndex] = '*'.code.toByte()
                }))
        }.isFailure)
    }
    @Test fun twoPeerSignedRemoteControlsAndRepliesRoundTrip() {
        val helper = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(256)
        }.generateKeyPair()
        val subject = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(256)
        }.generateKeyPair()
        for (kind in listOf(RemoteAssistStreamWire.TAP,
                RemoteAssistStreamWire.SWIPE, RemoteAssistStreamWire.BACK,
                RemoteAssistStreamWire.HOME, RemoteAssistStreamWire.RECENTS)) {
            val data = when (kind) {
                RemoteAssistStreamWire.TAP -> RemoteAssistStreamWire.tap(relation, session, 2000, 8000)
                RemoteAssistStreamWire.SWIPE -> RemoteAssistStreamWire.swipe(
                    relation, session, 2000, 8000, 2000, 3000)
                else -> RemoteAssistStreamWire.action(relation, session, kind)
            }
            val outgoing = envelope(kind, data)
            val incoming = LanTestWire.decode(LanTestWire.encode(SignedEnvelope(outgoing,
                SignedProtocol.sign(outgoing, helper.private))))
            assertTrue(SignedProtocol.verify(incoming.message, incoming.signature, helper.public))
            val decoded = RemoteAssistStreamWire.decode(incoming.message)
            assertEquals(session, decoded.sessionId)
            assertEquals(relation, decoded.relationId)
            val ack = envelope(RemoteAssistStreamWire.ACK,
                RemoteAssistStreamWire.ack(relation, session, outgoing.messageId.value))
            val verifiedAck = LanTestWire.decode(LanTestWire.encode(SignedEnvelope(ack,
                SignedProtocol.sign(ack, subject.private))))
            assertTrue(SignedProtocol.verify(verifiedAck.message, verifiedAck.signature, subject.public))
            assertEquals(outgoing.messageId.value,
                RemoteAssistStreamWire.decode(verifiedAck.message).respondsTo)
        }
    }

}
