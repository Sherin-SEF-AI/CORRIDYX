package ai.deepmost.corridyx.capture

import java.util.concurrent.TimeUnit
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Low-precision solar position (NOAA approximation) — accurate to ~0.1deg, which is plenty for
 * time-bucketing and low-sun-into-camera glare geometry. Pure math, no allocation, no network.
 */
object SunCalc {

    private const val DEG = Math.PI / 180.0
    private const val RAD = 180.0 / Math.PI

    /** @param epochMs UTC millis, [lat]/[lon] in degrees. */
    fun position(epochMs: Long, lat: Double, lon: Double): SunGeometry {
        // Julian day
        val jd = epochMs / 86_400_000.0 + 2440587.5
        val n = jd - 2451545.0
        // mean longitude / anomaly
        val L = (280.460 + 0.9856474 * n).mod(360.0)
        val g = (357.528 + 0.9856003 * n).mod(360.0) * DEG
        // ecliptic longitude
        val lambda = (L + 1.915 * sin(g) + 0.020 * sin(2 * g)) * DEG
        // obliquity
        val eps = (23.439 - 0.0000004 * n) * DEG
        // right ascension / declination
        val ra = atan2(cos(eps) * sin(lambda), cos(lambda))
        val dec = asin(sin(eps) * sin(lambda))
        // sidereal time
        val gmst = (6.697375 + 0.0657098242 * n + (epochMs % 86_400_000L) / 3_600_000.0).mod(24.0)
        val lmst = (gmst + lon / 15.0).mod(24.0)
        val ha = (lmst * 15.0 * DEG) - ra
        val latR = lat * DEG
        val elevation = asin(sin(latR) * sin(dec) + cos(latR) * cos(dec) * cos(ha))
        var azimuth = atan2(-sin(ha), tan(dec) * cos(latR) - sin(latR) * cos(ha))
        var azDeg = (azimuth * RAD + 360.0).mod(360.0)
        return SunGeometry(elevationDeg = elevation * RAD, azimuthDeg = azDeg)
    }

    /** DAY > 6deg, DUSK_DAWN within [-6,6], NIGHT < -6deg sun elevation. */
    fun bucket(sun: SunGeometry): TimeBucket = when {
        sun.elevationDeg > 6.0 -> TimeBucket.DAY
        sun.elevationDeg >= -6.0 -> TimeBucket.DUSK_DAWN
        else -> TimeBucket.NIGHT
    }

    private fun Double.mod(m: Double): Double {
        val r = this % m
        return if (r < 0) r + m else r
    }
}
