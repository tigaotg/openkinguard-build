package io.github.tigaotg.openkinguard.core.engine

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class HealthGattCapabilityDetectorTest {
    private fun uuid(id: Int) = "0000${id.toString(16).padStart(4, '0')}-0000-1000-8000-00805f9b34fb"

    @Test fun band8PublicDumpDoesNotPretendStandardHeartRateIsAvailable() {
        val capabilities = HealthGattCapabilityDetector.classify(mapOf(
            uuid(0x1800) to setOf(uuid(0x2A00)),
            uuid(0x180A) to setOf(uuid(0x2A29), uuid(0x2A24)),
            uuid(0xFE86).uppercase() to setOf(uuid(0xFE01).uppercase(), uuid(0xFE02)),
            uuid(0x1812) to setOf(uuid(0x2A4A))
        ))
        assertTrue(capabilities.hasHuaweiCommandAndNotify)
        assertTrue(capabilities.hasDeviceInformation)
        assertFalse(capabilities.hasStandardHeartRate)
        assertFalse(capabilities.hasStandardBattery)
    }

    @Test fun partialPrivateChannelIsNotClassifiedAsDuplex() {
        val capabilities = HealthGattCapabilityDetector.classify(
            mapOf(uuid(0xFE86) to setOf(uuid(0xFE01))))
        assertFalse(capabilities.hasHuaweiCommandAndNotify)
    }

    @Test fun standardHeartRateRequiresMeasurementCharacteristic() {
        val incomplete = HealthGattCapabilityDetector.classify(
            mapOf(uuid(0x180D) to emptySet()))
        val full = HealthGattCapabilityDetector.classify(
            mapOf(uuid(0x180D) to setOf(uuid(0x2A37)),
                uuid(0x180F) to setOf(uuid(0x2A19))))
        assertFalse(incomplete.hasStandardHeartRate)
        assertTrue(full.hasStandardHeartRate)
        assertTrue(full.hasStandardBattery)
        assertFalse(full.hasHuaweiCommandAndNotify)
    }
}
