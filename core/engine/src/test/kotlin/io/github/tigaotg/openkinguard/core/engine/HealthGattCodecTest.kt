package io.github.tigaotg.openkinguard.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class HealthGattCodecTest {
    @Test fun heartRateEightAndSixteenBit() {
        assertEquals("72", HealthGattCodec.decode(0x2A37, byteArrayOf(0, 72)).single().display)
        assertEquals(300.0, HealthGattCodec.decode(0x2A37,
            byteArrayOf(1, 0x2c, 1)).single().value, 0.0)
    }

    @Test fun pulseOximeterHasSaturationAndPulse() {
        val readings = HealthGattCodec.decode(0x2A5F, byteArrayOf(0, 98, 0, 70, 0))
        assertEquals(2, readings.size)
        assertEquals(HealthMetricKind.OXYGEN_SATURATION, readings[0].kind)
        assertEquals(98.0, readings[0].value, 0.0)
        assertEquals(HealthMetricKind.PULSE_RATE, readings[1].kind)
    }

    @Test fun bloodPressureReading() {
        val reading = HealthGattCodec.decode(0x2A35,
            byteArrayOf(0, 120, 0, 80, 0, 90, 0)).single()
        assertEquals("120/80", reading.display)
        assertEquals("mmHg", reading.unit)
        assertEquals(80.0, reading.secondary!!, 0.0)
    }

    @Test fun thermometerReading() {
        val reading = HealthGattCodec.decode(0x2A1C,
            byteArrayOf(0, 0x6d, 1, 0, (-1).toByte())).single()
        assertEquals(36.5, reading.value, 0.0001)
        assertEquals("°C", reading.unit)
    }

    @Test fun weightReading() {
        val reading = HealthGattCodec.decode(0x2A9D,
            byteArrayOf(0, 0x14, 0x37)).single()
        assertEquals(70.5, reading.value, 0.0001)
        assertEquals("kg", reading.unit)
    }

    @Test fun invalidAndUnknownPacketsCannotBecomeReadings() {
        assertTrue(HealthGattCodec.decode(0x2A37, byteArrayOf(1)).isEmpty())
        assertTrue(HealthGattCodec.decode(0x2A5F, byteArrayOf(0, 0xff.toByte(), 0x07,
            0xff.toByte(), 0x07)).isEmpty())
        assertTrue(HealthGattCodec.decode(0x2A9D, byteArrayOf(0)).isEmpty())
        assertTrue(HealthGattCodec.decode(0xFFFF, byteArrayOf(0, 1, 2)).isEmpty())
    }

    @Test fun catalogueExposesFiveStandardProfiles() {
        assertEquals(5, StandardHealthProfile.entries.size)
        assertEquals(StandardHealthProfile.HEART_RATE, StandardHealthProfile.fromService(0x180D))
        assertNull(StandardHealthProfile.fromService(0x1808))
    }
}
