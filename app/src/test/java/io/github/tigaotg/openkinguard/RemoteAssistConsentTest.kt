package io.github.tigaotg.openkinguard

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RemoteAssistConsentTest {
    private val relation = UUID.randomUUID().toString()
    private val fp = "a".repeat(64)

    @Test fun `signed invitation never grants control without local click`() {
        var now = 1_000L
        val subject = RemoteAssistConsent { now }
        val id = UUID.randomUUID().toString()
        assertTrue(subject.receiveRequest(relation, fp, id))
        assertEquals(RemoteAssistConsent.Phase.WAITING_FOR_PERSON, subject.snapshot()?.phase)
        assertFalse(subject.receiveRequest(relation, fp, UUID.randomUUID().toString()))
        assertEquals(RemoteAssistConsent.Phase.WAITING_FOR_PERSON, subject.snapshot()?.phase)
        subject.localDecision(id, true)
        assertEquals(RemoteAssistConsent.Phase.AWAITING_SCREEN_PERMISSION,
            subject.snapshot()?.phase)
        assertTrue(subject.stop(relation, fp, id))
        assertNull(subject.snapshot())
    }

    @Test fun `incoming request expires and cannot be approved later`() {
        var now = 1_000L
        val subject = RemoteAssistConsent { now }
        val id = UUID.randomUUID().toString()
        subject.receiveRequest(relation, fp, id)
        now += 60_001L
        assertNull(subject.snapshot())
        assertTrue(runCatching { subject.localDecision(id, true) }.isFailure)
    }

    @Test fun `remote acceptance must match our outstanding session and fingerprint`() {
        val helper = RemoteAssistConsent { 1_000L }
        val request = helper.request(relation, fp)
        assertFalse(helper.remoteDecision(relation, "b".repeat(64), request.sessionId, true))
        assertFalse(helper.remoteDecision(relation, fp, UUID.randomUUID().toString(), true))
        assertEquals(RemoteAssistConsent.Phase.WAITING_FOR_REPLY, helper.snapshot()?.phase)
        assertTrue(helper.remoteDecision(relation, fp, request.sessionId, true))
        assertEquals(RemoteAssistConsent.Phase.AWAITING_SCREEN_PERMISSION,
            helper.snapshot()?.phase)
        assertFalse(helper.remoteDecision(relation, fp, request.sessionId, true))
    }

    @Test fun `a rejection clears request and a terminated session cannot resume`() {
        val helper = RemoteAssistConsent { 1_000L }
        val request = helper.request(relation, fp)
        assertTrue(helper.remoteDecision(relation, fp, request.sessionId, false))
        assertNull(helper.snapshot())
        assertFalse(helper.remoteDecision(relation, fp, request.sessionId, true))
    }

    @Test fun `active capture requires local Android grant and expires fail closed`() {
        var now = 1_000L
        val subject = RemoteAssistConsent { now }
        val id = UUID.randomUUID().toString()
        subject.receiveRequest(relation, fp, id)
        assertFalse(subject.screenStarted(id))
        subject.localDecision(id, true)
        assertFalse(subject.screenStarted(UUID.randomUUID().toString()))
        assertTrue(subject.screenStarted(id))
        assertEquals(RemoteAssistConsent.Phase.ACTIVE, subject.snapshot()?.phase)
        now += 15 * 60_000L + 1
        assertNull(subject.snapshot())
        assertFalse(subject.screenStarted(id))
    }

    @Test fun `helper cannot send gestures before a verified screen preview`() {
        val helper = RemoteAssistConsent { 1_000L }
        val request = helper.request(relation, fp)
        assertFalse(helper.remotePreviewStarted(request.sessionId))
        helper.remoteDecision(relation, fp, request.sessionId, true)
        assertTrue(helper.remotePreviewStarted(request.sessionId))
        assertEquals(RemoteAssistConsent.Phase.ACTIVE, helper.snapshot()?.phase)
        assertTrue(helper.stop(relation, fp, request.sessionId))
        assertFalse(helper.remotePreviewStarted(request.sessionId))
    }
}
