package io.github.tigaotg.openkinguard.core.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HeartRatePilotTest {
    private val enabled = HeartRatePilotSettings(enabled = true, durationMillis = 60_000L)

    @Test fun `opt-in required and old readings do not become an alert on activation`() {
        val evaluator = HeartRatePilotEvaluator()
        assertNull(evaluator.observe(190, 1_000))
        evaluator.configure(enabled)
        assertEquals(HeartRatePilotPhase.NO_DATA, evaluator.snapshot(1_001, true).phase)
        assertNull(evaluator.observe(190, 100_000))
        assertNull(evaluator.observe(190, 150_000))
        assertNotNull(evaluator.observe(190, 160_000))
    }

    @Test fun `sustained low reading triggers once per continuous run`() {
        val evaluator = HeartRatePilotEvaluator(enabled)
        assertNull(evaluator.observe(40, 10_000))
        assertNull(evaluator.observe(45, 50_000))
        val alert = evaluator.observe(44, 70_000)
        assertNotNull(alert)
        assertTrue(alert!!.low)
        assertEquals(44, alert.bpm)
        assertEquals(HeartRatePilotPhase.ALERT_LOW, evaluator.snapshot(70_001, true).phase)
        assertNull(evaluator.observe(42, 80_000))
        evaluator.observe(70, 90_000) // normal sample ends episode
        assertEquals(HeartRatePilotPhase.NORMAL, evaluator.snapshot(90_000, true).phase)
        assertNull(evaluator.observe(39, 100_000))
        assertNotNull(evaluator.observe(39, 160_000))
    }

    @Test fun `high threshold is strictly greater and low strictly less`() {
        val evaluator = HeartRatePilotEvaluator(enabled)
        assertNull(evaluator.observe(120, 1_000))
        assertEquals(HeartRatePilotPhase.NORMAL, evaluator.snapshot(1_000, true).phase)
        assertNull(evaluator.observe(121, 2_000))
        assertNotNull(evaluator.observe(130, 62_000))
        assertEquals(HeartRatePilotPhase.ALERT_HIGH, evaluator.snapshot(63_000, true).phase)
    }

    @Test fun `brief spike is not a sustained episode`() {
        val evaluator = HeartRatePilotEvaluator(enabled)
        evaluator.observe(140, 10_000)
        evaluator.observe(80, 30_000)
        assertNull(evaluator.observe(145, 65_000))
        assertNull(evaluator.observe(145, 70_000))
    }

    @Test fun `stale reading prevents false alive indication and interrupts later run`() {
        val evaluator = HeartRatePilotEvaluator(enabled)
        evaluator.observe(140, 1_000)
        assertEquals(HeartRatePilotPhase.STALE, evaluator.snapshot(92_000, true).phase)
        assertNull(evaluator.observe(145, 200_000))
        assertEquals(HeartRatePilotPhase.COUNTING_HIGH, evaluator.snapshot(200_000, true).phase)
        assertNotNull(evaluator.observe(145, 260_000))
    }

    @Test fun `disconnect and reconfigure both clear old data`() {
        val evaluator = HeartRatePilotEvaluator(enabled)
        evaluator.observe(150, 1_000)
        evaluator.reset()
        assertEquals(HeartRatePilotPhase.DISCONNECTED, evaluator.snapshot(3_000, false).phase)
        assertEquals(HeartRatePilotPhase.NO_DATA, evaluator.snapshot(3_000, true).phase)
        evaluator.observe(150, 4_000)
        evaluator.configure(enabled.copy(highBpm = 140))
        assertEquals(HeartRatePilotPhase.NO_DATA, evaluator.snapshot(5_000, true).phase)
    }

    @Test fun `duplicate or backward clock does not extend a run`() {
        val evaluator = HeartRatePilotEvaluator(enabled)
        evaluator.observe(150, 1_000)
        assertNull(evaluator.observe(150, 1_000))
        assertEquals(HeartRatePilotPhase.NO_DATA, evaluator.snapshot(1_000, true).phase)
        evaluator.observe(150, 10_000)
        assertNull(evaluator.observe(150, 9_000))
        assertNull(evaluator.observe(150, 70_000))
    }

    @Test fun `malformed measurements cannot drive threshold decision`() {
        val evaluator = HeartRatePilotEvaluator(enabled)
        evaluator.observe(150, 1_000)
        assertNull(evaluator.observe(400, 20_000))
        assertEquals(HeartRatePilotPhase.NO_DATA, evaluator.snapshot(20_000, true).phase)
        assertNull(evaluator.observe(0, 80_000))
        assertNull(evaluator.observe(140, -1))
    }

    @Test fun `disable immediately stops evaluation`() {
        val evaluator = HeartRatePilotEvaluator(enabled)
        evaluator.observe(150, 1_000)
        evaluator.configure(enabled.copy(enabled = false))
        assertEquals(HeartRatePilotPhase.OFF, evaluator.snapshot(70_000, true).phase)
        assertNull(evaluator.observe(150, 70_000))
    }

    @Test fun `invalid settings rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            HeartRatePilotSettings(enabled = true, lowBpm = 99, highBpm = 95)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeartRatePilotSettings(enabled = true, durationMillis = 10_000)
        }
    }
}
