package io.github.tigaotg.openkinguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import io.github.tigaotg.openkinguard.security.protocol.S3LocationWire
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.roundToInt

internal data class LocalGpsSelfCheck(
    val finePermission: Boolean,
    val coarsePermission: Boolean,
    val gpsProviderEnabled: Boolean,
    val networkProviderEnabled: Boolean,
    val fix: S3LocationWire.Fix?,
)

/** Android LocationManager only: no Google Play services and no background location access. */
internal class S3GpsCapture(private val app: Context) {
    private val location = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val lock = ReentrantLock()

    fun permitted(): Boolean = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    /** A foreground, local-only check; network fixes must not be mistaken for GPS satellites. */
    fun selfCheck(): LocalGpsSelfCheck {
        val fine = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val gpsOn = enabled(LocationManager.GPS_PROVIDER)
        val networkOn = enabled(LocationManager.NETWORK_PROVIDER)
        val fix = if ((fine || coarse) && ((fine && gpsOn) || networkOn))
            capture(10_000L) else null
        return LocalGpsSelfCheck(fine, coarse, gpsOn, networkOn, fix)
    }

    fun capture(timeoutMillis: Long = 8_000L): S3LocationWire.Fix? {
        require(timeoutMillis in 1_000L..12_000L)
        if (!permitted()) return null
        if (!lock.tryLock()) return null // Avoid concurrent location hardware sessions.
        try {
            val fine = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            val providers = listOfNotNull(
                LocationManager.GPS_PROVIDER.takeIf { fine && enabled(it) },
                LocationManager.NETWORK_PROVIDER.takeIf { enabled(it) })
            if (providers.isEmpty()) return null

            // Only a fresh *precise* cached fix may shortcut satellite acquisition.
            // A coarse network cache is a fallback, not a reason to stop early.
            val cached = providers.mapNotNull { provider ->
                runCatching { location.getLastKnownLocation(provider) }.getOrNull()
            }.filter { usable(it, 30_000L) }.minByOrNull { it.accuracy }
            if (cached != null && S3FixQuality.canUseCacheImmediately(
                    ageMillis(cached), cached.accuracy)) return encoded(cached)

            val latch = CountDownLatch(1)
            val best = AtomicReference<Location?>(cached)
            val listener = object : LocationListener {
                override fun onLocationChanged(update: Location) {
                    if (usable(update, 30_000L)) {
                        val existing = best.get()
                        if (existing == null || update.accuracy < existing.accuracy) best.set(update)
                        if (S3FixQuality.goodEnoughToFinish(update.accuracy)) {
                            latch.countDown()
                        }
                    }
                }
                override fun onProviderDisabled(provider: String) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                @Suppress("DEPRECATION")
                override fun onStatusChanged(provider: String, status: Int,
                                             extras: android.os.Bundle?) = Unit
            }
            var registered = false
            try {
                providers.forEach { provider ->
                    runCatching {
                        location.requestLocationUpdates(provider, 0L, 0f, listener,
                            Looper.getMainLooper())
                        registered = true
                    }
                }
                if (registered) latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
            } finally {
                if (registered) runCatching { location.removeUpdates(listener) }
            }
            return best.get()?.let(::encoded)
        } finally { lock.unlock() }
    }

    private fun enabled(provider: String): Boolean =
        runCatching { location.isProviderEnabled(provider) }.getOrDefault(false)

    private fun ageMillis(fix: Location): Long = TimeUnit.NANOSECONDS.toMillis(
        SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos)

    private fun usable(fix: Location, maxAgeMillis: Long): Boolean {
        val age = ageMillis(fix)
        return age in 0..maxAgeMillis && fix.hasAccuracy() && fix.accuracy > 0f &&
            fix.accuracy <= 5_000f && fix.latitude in -90.0..90.0 &&
            fix.longitude in -180.0..180.0
    }

    private fun encoded(fix: Location): S3LocationWire.Fix {
        val elapsed = ageMillis(fix).coerceAtLeast(0L)
        return S3LocationWire.Fix((fix.latitude * 10_000_000).roundToInt(),
            (fix.longitude * 10_000_000).roundToInt(),
            (fix.accuracy * 100).roundToInt().coerceIn(1, 500_000),
            System.currentTimeMillis() - elapsed,
            if (fix.provider == LocationManager.GPS_PROVIDER) "G" else "N")
    }
}
