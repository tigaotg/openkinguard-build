package io.github.tigaotg.openkinguard.core.engine

import java.util.Locale
import kotlin.math.pow

/** Bluetooth SIG standard GATT profile catalogue, independent of phone/brand SDKs. */
enum class StandardHealthProfile(
    val label: String, val serviceId: Int, val characteristicIds: List<Int>
) {
    HEART_RATE("心率设备", 0x180D, listOf(0x2A37)),
    PULSE_OXIMETER("血氧仪", 0x1822, listOf(0x2A5F, 0x2A5E)),
    BLOOD_PRESSURE("血压计", 0x1810, listOf(0x2A35)),
    THERMOMETER("体温计", 0x1809, listOf(0x2A1C, 0x2A1E)),
    WEIGHT_SCALE("体重秤", 0x181D, listOf(0x2A9D));

    companion object {
        fun fromService(id: Int): StandardHealthProfile? = entries.firstOrNull { it.serviceId == id }
    }
}

enum class HealthMetricKind(val label: String) {
    HEART_RATE("心率"), PULSE_RATE("脉率"), OXYGEN_SATURATION("血氧饱和度"),
    BLOOD_PRESSURE("血压"), BODY_TEMPERATURE("体温"), BODY_WEIGHT("体重")
}

data class HealthMetricValue(
    val kind: HealthMetricKind,
    val value: Double,
    val unit: String,
    val display: String,
    val secondary: Double? = null
)

/**
 * Strict parsers for the initial fields of standard BLE measurements.
 * Invalid/unknown data is discarded rather than displayed as a physiological reading.
 * This is not a medical-device calibration or diagnosis engine.
 */
object HealthGattCodec {
    fun decode(characteristicId: Int, bytes: ByteArray): List<HealthMetricValue> = when (characteristicId) {
        0x2A37 -> heartRate(bytes)
        0x2A5F, 0x2A5E -> pulseOximeter(bytes)
        0x2A35 -> bloodPressure(bytes)
        0x2A1C, 0x2A1E -> thermometer(bytes)
        0x2A9D -> weight(bytes)
        else -> emptyList()
    }

    private fun heartRate(data: ByteArray): List<HealthMetricValue> {
        if (data.size < 2) return emptyList()
        val is16 = (u8(data, 0) and 1) != 0
        val value = if (is16) u16(data, 1) else u8(data, 1)
        if (value == null || value !in 1..400) return emptyList()
        return listOf(HealthMetricValue(HealthMetricKind.HEART_RATE, value.toDouble(), "次/分", "$value"))
    }

    private fun pulseOximeter(data: ByteArray): List<HealthMetricValue> {
        if (data.size < 5) return emptyList()
        val saturation = sfloat(data, 1)
        val pulse = sfloat(data, 3)
        val values = mutableListOf<HealthMetricValue>()
        if (saturation != null && saturation in 0.0..100.0) {
            values += HealthMetricValue(HealthMetricKind.OXYGEN_SATURATION,
                saturation, "%", number(saturation))
        }
        if (pulse != null && pulse in 1.0..400.0) {
            values += HealthMetricValue(HealthMetricKind.PULSE_RATE,
                pulse, "次/分", number(pulse))
        }
        return values
    }

    private fun bloodPressure(data: ByteArray): List<HealthMetricValue> {
        if (data.size < 7) return emptyList()
        val systolic = sfloat(data, 1) ?: return emptyList()
        val diastolic = sfloat(data, 3) ?: return emptyList()
        val unit = if ((u8(data, 0) and 1) == 0) "mmHg" else "kPa"
        if (systolic !in 1.0..400.0 || diastolic !in 1.0..400.0) return emptyList()
        return listOf(HealthMetricValue(HealthMetricKind.BLOOD_PRESSURE,
            systolic, unit, "${number(systolic)}/${number(diastolic)}", diastolic))
    }

    private fun thermometer(data: ByteArray): List<HealthMetricValue> {
        if (data.size < 5) return emptyList()
        val value = float11073(data, 1) ?: return emptyList()
        val unit = if ((u8(data, 0) and 1) == 0) "°C" else "°F"
        if (value !in -100.0..250.0) return emptyList()
        return listOf(HealthMetricValue(HealthMetricKind.BODY_TEMPERATURE,
            value, unit, number(value)))
    }

    private fun weight(data: ByteArray): List<HealthMetricValue> {
        if (data.size < 3) return emptyList()
        val raw = u16(data, 1) ?: return emptyList()
        val isImperial = (u8(data, 0) and 1) != 0
        val value = raw * if (isImperial) 0.01 else 0.005
        if (value <= 0.0 || value > 1000.0) return emptyList()
        return listOf(HealthMetricValue(HealthMetricKind.BODY_WEIGHT,
            value, if (isImperial) "lb" else "kg", number(value)))
    }

    private fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xff

    private fun u16(data: ByteArray, offset: Int): Int? =
        if (offset + 1 >= data.size) null else u8(data, offset) or (u8(data, offset + 1) shl 8)

    /** IEEE-11073 16-bit SFLOAT: 12-bit signed mantissa and 4-bit signed decimal exponent. */
    private fun sfloat(data: ByteArray, offset: Int): Double? {
        val raw = u16(data, offset) ?: return null
        var mantissa = raw and 0x0fff
        if (mantissa in 0x07fe..0x0802) return null // Reserved NaN/Inf/NRes values.
        if ((mantissa and 0x0800) != 0) mantissa -= 0x1000
        var exponent = raw shr 12
        if ((exponent and 0x08) != 0) exponent -= 16
        return mantissa * 10.0.pow(exponent)
    }

    /** IEEE-11073 32-bit FLOAT: 24-bit signed mantissa + 8-bit signed exponent. */
    private fun float11073(data: ByteArray, offset: Int): Double? {
        if (offset + 3 >= data.size) return null
        var mantissa = u8(data, offset) or (u8(data, offset + 1) shl 8) or
            (u8(data, offset + 2) shl 16)
        if (mantissa in 0x7ffffe..0x800002) return null
        if ((mantissa and 0x800000) != 0) mantissa -= 0x1000000
        val exponent = data[offset + 3].toInt()
        return (mantissa * 10.0.pow(exponent)).takeIf { it.isFinite() }
    }

    private fun number(value: Double): String = String.format(Locale.ROOT,
        if (value % 1.0 == 0.0) "%.0f" else "%.1f", value)
}
