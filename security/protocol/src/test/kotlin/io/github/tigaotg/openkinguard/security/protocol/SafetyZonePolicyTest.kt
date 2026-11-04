package io.github.tigaotg.openkinguard.security.protocol

import org.junit.Assert.*
import org.junit.Test

class SafetyZonePolicyTest {
    private val start = 1_800_000_000_000L
    private fun zone(radius: Int = 300) = SafetyZonePolicy.create(0, 0, radius, start)

    @Test fun `haversine handles both antimeridian and known distances`() {
        assertEquals(1111.95, SafetyZonePolicy.distanceMeters(0, 0, 0, 100_000), 2.0)
        assertEquals(22_239.0, SafetyZonePolicy.distanceMeters(
            0, 1_799_000_000, 0, -1_799_000_000), 2.0)
    }

    @Test fun `exit and return need two separated, accurate and fresh points`() {
        val first = SafetyZonePolicy.observe(zone(), 0, 100_000, 500,
            start + 10_000)
        assertNull(first.transition)
        assertEquals(SafetyZonePolicy.Position.INSIDE, first.zone.position)

        val left = SafetyZonePolicy.observe(first.zone, 0, 100_000, 500,
            start + 16_000)
        assertEquals(SafetyZonePolicy.Transition.LEFT, left.transition)
        assertEquals(SafetyZonePolicy.Position.OUTSIDE, left.zone.position)
        assertEquals(1112, left.zone.lastDistanceMeters)

        val beganReturn = SafetyZonePolicy.observe(left.zone, 0, 0, 500,
            start + 23_000)
        assertNull(beganReturn.transition)
        val returned = SafetyZonePolicy.observe(beganReturn.zone, 0, 0, 500,
            start + 29_000)
        assertEquals(SafetyZonePolicy.Transition.RETURNED, returned.transition)
    }

    @Test fun `duplicate stale and out-of-order points cannot confirm transition`() {
        val first = SafetyZonePolicy.observe(zone(), 0, 100_000, 500, start + 10_000)
        val same = SafetyZonePolicy.observe(first.zone, 0, 100_000, 500, start + 10_000)
        assertEquals(first.zone, same.zone)
        assertNull(same.transition)
        val older = SafetyZonePolicy.observe(first.zone, 0, 100_000, 500, start + 9_000)
        assertEquals(first.zone, older.zone)
        val tooSoon = SafetyZonePolicy.observe(first.zone, 0, 100_000, 500,
            start + 12_000)
        assertNull(tooSoon.transition)
        assertEquals(SafetyZonePolicy.Position.INSIDE, tooSoon.zone.position)
    }

    @Test fun `imprecise or border fixes never trigger false exit`() {
        val first = SafetyZonePolicy.observe(zone(), 0, 100_000, 500,
            start + 10_000)
        val noisy = SafetyZonePolicy.observe(first.zone, 0, 100_000, 20_000,
            start + 16_000)
        assertNull(noisy.transition)
        assertEquals(SafetyZonePolicy.Position.UNKNOWN, noisy.observed)
        assertEquals(SafetyZonePolicy.Position.UNKNOWN, noisy.zone.candidate)
        val onBorder = SafetyZonePolicy.observe(noisy.zone, 0, 27_000, 500,
            start + 25_000)
        assertEquals(SafetyZonePolicy.Position.UNKNOWN, onBorder.observed)
        assertEquals(SafetyZonePolicy.Position.INSIDE, onBorder.zone.position)
        val next = SafetyZonePolicy.observe(onBorder.zone, 0, 100_000, 500,
            start + 32_000)
        assertNull(next.transition)
    }

    @Test fun `radius change resets confirmation and rejects invalid configuration`() {
        val first = SafetyZonePolicy.observe(zone(), 0, 100_000, 500,
            start + 10_000)
        val resized = SafetyZonePolicy.withRadius(first.zone, 1000)
        assertEquals(SafetyZonePolicy.Position.UNKNOWN, resized.position)
        assertEquals(SafetyZonePolicy.Position.UNKNOWN, resized.candidate)
        val baseline1 = SafetyZonePolicy.observe(resized, 0, 100_000, 500,
            start + 16_000)
        val baseline2 = SafetyZonePolicy.observe(baseline1.zone, 0, 100_000, 500,
            start + 22_000)
        assertNull(baseline2.transition)
        assertEquals(SafetyZonePolicy.Position.OUTSIDE, baseline2.zone.position)
        assertThrows(IllegalArgumentException::class.java) { SafetyZonePolicy.create(0, 0, 0, start) }
        assertThrows(IllegalArgumentException::class.java) { SafetyZonePolicy.create(910_000_000, 0, 300, start) }
    }
}
