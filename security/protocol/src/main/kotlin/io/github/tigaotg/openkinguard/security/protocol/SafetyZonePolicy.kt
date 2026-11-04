package io.github.tigaotg.openkinguard.security.protocol

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Local-only, opt-in safety-zone inspection of *signed, requested* GPS fixes.
 * A zone is not a background geofence: no new location is sampled by this policy.
 */
object SafetyZonePolicy {
    val allowedRadiiMeters = setOf(100, 300, 500, 1_000, 2_000)
    private const val MARGIN_METERS = 20.0
    private const val MIN_CONFIRMATION_GAP_MS = 5_000L

    enum class Position { UNKNOWN, INSIDE, OUTSIDE }
    enum class Transition { LEFT, RETURNED }

    data class Zone(
        val centerLatitudeE7: Int,
        val centerLongitudeE7: Int,
        val radiusMeters: Int,
        val createdAtMillis: Long,
        val position: Position = Position.INSIDE,
        val candidate: Position = Position.UNKNOWN,
        val candidateSinceMillis: Long = 0,
        val lastSampleAtMillis: Long = 0,
        val lastDistanceMeters: Int = 0,
    )

    data class Result(val zone: Zone, val transition: Transition? = null,
                      val observed: Position = Position.UNKNOWN)

    fun create(latitudeE7: Int, longitudeE7: Int, radiusMeters: Int,
               capturedAtMillis: Long): Zone {
        LocationMapPolicy.coordinates(latitudeE7, longitudeE7)
        require(radiusMeters in allowedRadiiMeters) { "安全区域半径不受支持" }
        require(capturedAtMillis > 0) { "安全区域中心必须来自有效的定位" }
        return Zone(latitudeE7, longitudeE7, radiusMeters, capturedAtMillis)
    }

    fun withRadius(zone: Zone, radiusMeters: Int): Zone {
        require(radiusMeters in allowedRadiiMeters) { "安全区域半径不受支持" }
        // Changing the boundary invalidates all previously inferred positions.
        return zone.copy(radiusMeters = radiusMeters, position = Position.UNKNOWN,
            candidate = Position.UNKNOWN, candidateSinceMillis = 0)
    }

    fun distanceMeters(latA: Int, lonA: Int, latB: Int, lonB: Int): Double {
        LocationMapPolicy.coordinates(latA, lonA)
        LocationMapPolicy.coordinates(latB, lonB)
        val lat1 = Math.toRadians(latA / 1e7)
        val lat2 = Math.toRadians(latB / 1e7)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians((lonB.toLong() - lonA.toLong()) / 1e7)
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 6_371_000.0 * 2.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Two distinct, sufficiently spaced and unambiguous samples confirm a change.
     * A noisy fix near the boundary cancels the pending change and leaves the
     * previous confirmed position intact. Old or duplicate fixes are ignored.
     */
    fun observe(zone: Zone, latitudeE7: Int, longitudeE7: Int, accuracyCm: Int,
                capturedAtMillis: Long): Result {
        if (capturedAtMillis <= zone.createdAtMillis ||
            capturedAtMillis <= zone.lastSampleAtMillis) return Result(zone)
        require(accuracyCm >= 0) { "定位精度无效" }
        val distance = distanceMeters(zone.centerLatitudeE7, zone.centerLongitudeE7,
            latitudeE7, longitudeE7)
        val accuracy = accuracyCm / 100.0
        val observed = when {
            accuracy > zone.radiusMeters / 2.0 -> Position.UNKNOWN
            distance + accuracy + MARGIN_METERS < zone.radiusMeters -> Position.INSIDE
            distance - accuracy - MARGIN_METERS > zone.radiusMeters -> Position.OUTSIDE
            else -> Position.UNKNOWN
        }
        val seen = zone.copy(lastSampleAtMillis = capturedAtMillis,
            lastDistanceMeters = distance.roundToInt())
        if (observed == Position.UNKNOWN || observed == zone.position)
            return Result(seen.copy(candidate = Position.UNKNOWN, candidateSinceMillis = 0),
                observed = observed)
        if (observed != zone.candidate) return Result(seen.copy(candidate = observed,
            candidateSinceMillis = capturedAtMillis), observed = observed)
        if (capturedAtMillis - zone.candidateSinceMillis < MIN_CONFIRMATION_GAP_MS)
            return Result(seen, observed = observed)
        val transition = when {
            zone.position == Position.INSIDE && observed == Position.OUTSIDE -> Transition.LEFT
            zone.position == Position.OUTSIDE && observed == Position.INSIDE -> Transition.RETURNED
            else -> null // First observation following a boundary edit is only a baseline.
        }
        return Result(seen.copy(position = observed, candidate = Position.UNKNOWN,
            candidateSinceMillis = 0), transition, observed)
    }
}
