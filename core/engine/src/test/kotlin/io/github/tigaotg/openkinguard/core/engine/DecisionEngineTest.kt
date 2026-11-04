package io.github.tigaotg.openkinguard.core.engine

import io.github.tigaotg.openkinguard.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DecisionEngineTest {
    private val engine = DecisionEngine()
    private val a = UserId("A")
    private val b = UserId("B")
    private val c = UserId("C")
    private val d = UserId("D")
    private val aDevice = DeviceId("A-phone")
    private val clock = ClockReading(epochMillis = 1_800_000_000_000, elapsedRealtimeMillis = 100_000)
    private val all = setOf(RelationPermission.RECEIVE_ALERTS, RelationPermission.RESPOND_TO_ALERTS)

    private fun relation(id: String, subject: UserId, helper: UserId, permissions: Set<RelationPermission> = all) =
        HelpRelation(RelationId(id), subject, helper, permissions)

    private fun state(vararg relations: HelpRelation) = GuardState(
        localUserId = a,
        localDeviceId = aDevice,
        relations = relations.associateBy { it.id }
    )

    private fun sos(id: String = "SOS-A") = GuardEvent.ManualSosRequested(EventId(id), a, aDevice)

    @Test fun `SOS is possible without BLE GPS or any helper`() {
        val result = engine.evaluate(state(), sos(), GuardRules(), clock)
        assertEquals(IncidentStatus.OPEN, result.state.incidents[EventId("SOS-A")]?.status)
        assertEquals(listOf(GuardCommand.WarnLocally(LocalWarning.NO_AUTHORIZED_HELPERS)), result.commands)
    }

    @Test fun `only explicit directed relations receive the subject alert`() {
        val input = state(
            relation("B-helps-A", a, b),
            relation("A-helps-B", b, a),
            relation("A-helps-C", c, a),
            relation("D-helps-A", a, d)
        )
        val result = engine.evaluate(input, sos(), GuardRules(), clock)
        val command = result.commands.single() as GuardCommand.DispatchEmergency
        assertEquals(setOf(b, d), command.recipientIds)
    }

    @Test fun `revoking B helps A does not revoke A helps B`() {
        val r1 = relation("B-helps-A", a, b)
        val r2 = relation("A-helps-B", b, a)
        val result = engine.evaluate(state(r1, r2), GuardEvent.RelationWithdrawn(r1.id, a), GuardRules(), clock)
        assertEquals(RelationStatus.REVOKED, result.state.relations[r1.id]?.status)
        assertEquals(RelationStatus.ACTIVE, result.state.relations[r2.id]?.status)
    }

    @Test fun `an unrelated user cannot revoke a relationship`() {
        val r = relation("B-helps-A", a, b)
        val result = engine.evaluate(state(r), GuardEvent.RelationWithdrawn(r.id, c), GuardRules(), clock)
        assertEquals(state(r), result.state)
    }

    @Test fun `revoked relation ID cannot be resurrected`() {
        val r = relation("R", a, b)
        val withdrawn = engine.evaluate(state(r), GuardEvent.RelationWithdrawn(r.id, a), GuardRules(), clock)
        val result = engine.evaluate(withdrawn.state, GuardEvent.RelationEstablished(r), GuardRules(), clock)
        assertEquals(RelationStatus.REVOKED, result.state.relations[r.id]?.status)
    }

    @Test fun `identical SOS event ID does not produce another command`() {
        val initial = state(relation("R", a, b))
        val first = engine.evaluate(initial, sos(), GuardRules(), clock)
        val second = engine.evaluate(first.state, sos(), GuardRules(), clock)
        assertEquals(1, second.state.incidents.size)
        assertTrue(second.commands.isEmpty())
    }

    @Test fun `SOS cannot be forged as a local event of another user or device`() {
        val initial = state(relation("R", a, b))
        assertEquals(initial, engine.evaluate(initial, GuardEvent.ManualSosRequested(EventId("X"), b, aDevice), GuardRules(), clock).state)
        assertEquals(initial, engine.evaluate(initial, GuardEvent.ManualSosRequested(EventId("X"), a, DeviceId("OTHER")), GuardRules(), clock).state)
    }

    @Test fun `first authorized takeover wins, second cannot replace it`() {
        val initial = engine.evaluate(state(relation("R1", a, b), relation("R2", a, d)), sos(), GuardRules(), clock)
        val first = engine.evaluate(initial.state, GuardEvent.TakeoverRequested(EventId("SOS-A"), b), GuardRules(), clock)
        val second = engine.evaluate(first.state, GuardEvent.TakeoverRequested(EventId("SOS-A"), d), GuardRules(), clock)
        assertEquals(b, second.state.incidents[EventId("SOS-A")]?.acceptedBy)
        assertEquals(IncidentStatus.ACCEPTED, second.state.incidents[EventId("SOS-A")]?.status)
        assertTrue(second.commands.isEmpty())
    }

    @Test fun `helper with receive permission cannot take over without respond permission`() {
        val onlyReceive = relation("R", a, b, setOf(RelationPermission.RECEIVE_ALERTS))
        val first = engine.evaluate(state(onlyReceive), sos(), GuardRules(), clock)
        val result = engine.evaluate(first.state, GuardEvent.TakeoverRequested(EventId("SOS-A"), b), GuardRules(), clock)
        assertEquals(IncidentStatus.OPEN, result.state.incidents[EventId("SOS-A")]?.status)
    }

    @Test fun `a takeover is not a closure, even if helper has acknowledged it`() {
        val first = engine.evaluate(state(relation("R", a, b)), sos(), GuardRules(), clock)
        val result = engine.evaluate(first.state, GuardEvent.TakeoverRequested(EventId("SOS-A"), b), GuardRules(), clock)
        assertEquals(IncidentStatus.ACCEPTED, result.state.incidents[EventId("SOS-A")]?.status)
        assertNull(result.state.incidents[EventId("SOS-A")]?.resolution)
    }

    @Test fun `closure requires explicit confirmation and authorized actor`() {
        val first = engine.evaluate(state(relation("R", a, b)), sos(), GuardRules(), clock)
        val noConfirmation = engine.evaluate(first.state, GuardEvent.ClosureRequested(EventId("SOS-A"), a, Resolution.FALSE_ALARM, false), GuardRules(), clock)
        val unauthorized = engine.evaluate(noConfirmation.state, GuardEvent.ClosureRequested(EventId("SOS-A"), c, Resolution.FALSE_ALARM, true), GuardRules(), clock)
        assertEquals(IncidentStatus.OPEN, unauthorized.state.incidents[EventId("SOS-A")]?.status)
        val close = engine.evaluate(unauthorized.state, GuardEvent.ClosureRequested(EventId("SOS-A"), a, Resolution.FALSE_ALARM, true), GuardRules(), clock)
        assertEquals(IncidentStatus.CLOSED, close.state.incidents[EventId("SOS-A")]?.status)
    }

    @Test fun `remote subject events require directed receive permission`() {
        val remote = Incident(EventId("B-SOS"), b, DeviceId("B-phone"), IncidentType.MANUAL_SOS, clock.epochMillis)
        val refused = engine.evaluate(state(relation("R", a, b)), GuardEvent.RemoteIncidentAnnounced(remote), GuardRules(), clock)
        assertTrue(refused.state.incidents.isEmpty())
        val accepted = engine.evaluate(state(relation("R", b, a)), GuardEvent.RemoteIncidentAnnounced(remote), GuardRules(), clock)
        assertEquals(remote, accepted.state.incidents[remote.id])
    }

    private val rateRules = GuardRules(HeartRateRule(lowBpm = 45, highBpm = 125, requiredDurationMillis = 30_000, enabled = true))
    private fun rate(id: String, bpm: Int?, at: Long, quality: HeartRateQuality = HeartRateQuality.VALID) =
        GuardEvent.HeartRateObserved(EventId(id), a, aDevice, bpm, quality, at)

    @Test fun `sustained abnormal heart rate produces one incident and no automatic closure`() {
        val begin = engine.evaluate(state(relation("R", a, b)), rate("H1", 150, 60_000), rateRules, clock.copy(elapsedRealtimeMillis = 60_000))
        assertTrue(begin.state.incidents.isEmpty())
        val raised = engine.evaluate(begin.state, rate("H2", 150, 90_000), rateRules, clock.copy(elapsedRealtimeMillis = 90_000))
        assertEquals(1, raised.state.incidents.size)
        val normal = engine.evaluate(raised.state, rate("H3", 72, 95_000), rateRules, clock.copy(elapsedRealtimeMillis = 95_000))
        assertEquals(IncidentStatus.OPEN, normal.state.incidents[EventId("H2")]?.status)
        assertEquals(1, normal.state.incidents.size)
    }

    @Test fun `stale and not-worn samples cannot trigger severe incident`() {
        val initial = state(relation("R", a, b))
        val stale = engine.evaluate(initial, rate("H1", 150, 0), rateRules, clock.copy(elapsedRealtimeMillis = 100_000))
        val notWorn = engine.evaluate(stale.state, rate("H2", null, 105_000, HeartRateQuality.NOT_WORN), rateRules, clock.copy(elapsedRealtimeMillis = 105_000))
        assertTrue(notWorn.state.incidents.isEmpty())
    }

    @Test fun `monitoring disabled never generates a heart rate alarm`() {
        val disabled = GuardRules(HeartRateRule(45, 125, 0, enabled = false))
        val result = engine.evaluate(state(), rate("H", 150, 100_000), disabled, clock)
        assertTrue(result.state.incidents.isEmpty())
    }

    @Test fun `large sample gap cannot masquerade as continuous abnormality`() {
        val begin = engine.evaluate(state(), rate("H1", 150, 0), rateRules, clock.copy(elapsedRealtimeMillis = 0))
        val second = engine.evaluate(begin.state, rate("H2", 150, 100_000), rateRules, clock.copy(elapsedRealtimeMillis = 100_000))
        assertTrue(second.state.incidents.isEmpty())
    }
}
