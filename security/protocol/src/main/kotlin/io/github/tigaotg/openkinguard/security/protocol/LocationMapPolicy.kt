package io.github.tigaotg.openkinguard.security.protocol

import java.util.Locale

/** WGS84 coordinates are not converted by this app. Maps must receive the correct datum. */
object LocationMapPolicy {
    const val STALE_AFTER_MS = 5 * 60_000L

    fun coordinates(latitudeE7: Int, longitudeE7: Int): String {
        require(latitudeE7 in -900_000_000..900_000_000 &&
            longitudeE7 in -1_800_000_000..1_800_000_000) { "定位坐标超出有效范围" }
        return String.format(Locale.US, "%.7f,%.7f",
            latitudeE7 / 10_000_000.0, longitudeE7 / 10_000_000.0)
    }

    fun isHistorical(capturedAtMillis: Long, nowMillis: Long): Boolean =
        capturedAtMillis <= 0L || capturedAtMillis > nowMillis + 60_000L ||
            nowMillis - capturedAtMillis > STALE_AFTER_MS
}
