package io.github.tigaotg.openkinguard.core.engine

import java.util.Locale

/** Protocol capability hints from UUID metadata, not proof of authentication or data access. */
data class HealthGattCapabilities(
    val hasStandardHeartRate: Boolean,
    val hasStandardBattery: Boolean,
    val hasDeviceInformation: Boolean,
    val hasHuaweiCommandAndNotify: Boolean
)

object HealthGattCapabilityDetector {
    private fun normalized(value: String) = value.lowercase(Locale.ROOT)
    private fun uuid(shortId: Int) = "0000${shortId.toString(16).padStart(4, '0')}-0000-1000-8000-00805f9b34fb"

    fun classify(services: Map<String, Set<String>>): HealthGattCapabilities {
        val all = services.mapKeys { normalized(it.key) }
            .mapValues { (_, values) -> values.map(::normalized).toSet() }
        val heartRate = all[uuid(0x180D)].orEmpty().contains(uuid(0x2A37))
        val battery = all[uuid(0x180F)].orEmpty().contains(uuid(0x2A19))
        val info = all.containsKey(uuid(0x180A))
        val huawei = all[uuid(0xFE86)].orEmpty()
        return HealthGattCapabilities(heartRate, battery, info,
            huawei.contains(uuid(0xFE01)) && huawei.contains(uuid(0xFE02)))
    }
}
