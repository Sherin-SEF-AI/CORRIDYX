package ai.deepmost.corridyx.capture

/** Time-of-day bucket derived from sun elevation (see [SunCalc]). */
enum class TimeBucket { DAY, DUSK_DAWN, NIGHT }

/** Reported raw-GNSS capability of this device/session — recorded in packet capability block. */
enum class GnssCapability { RAW_MEASUREMENTS, STATUS_ONLY, NONE }

/** Immutable location fix snapshot from FusedLocationProvider. */
data class LocationSnapshot(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,      // horizontal accuracy estimate
    val speedMps: Float,
    val bearingDeg: Float,     // movement heading, 0..360
    val hasBearing: Boolean,
    val timestampMs: Long,
)

/** GnssStatus + (optional) raw-measurement-derived quality snapshot for the gnss_q head. */
data class GnssSnapshot(
    val satellitesInView: Int,
    val satellitesUsedInFix: Int,
    val meanCn0: Float,            // dB-Hz averaged over used sats
    val topQuartileCn0: Float,     // dB-Hz of the strongest quartile
    val constellations: Set<Int>,  // GnssStatus.CONSTELLATION_* present among used sats
    val cn0Variance: Float,        // multipath proxy (variance of used-sat C/N0)
    val pseudorangeRateJitter: Float, // raw-measurement multipath proxy; 0 if unsupported
    val capability: GnssCapability,
    val timestampMs: Long,
)

/**
 * A bounded window of the 100 Hz IMU ring buffer captured for one analysis tick.
 * Holds the vertical (gravity-aligned) acceleration series plus gyro magnitude series so the
 * roughness head can band-pass, RMS, count spikes, and run the mount-resonance sanity check.
 */
class ImuWindow(
    val vertAccel: FloatArray,   // gravity-removed vertical acceleration, m/s^2
    val gyroMag: FloatArray,     // angular-rate magnitude, rad/s
    val sampleRateHz: Float,
    val timestampNs: Long,
) {
    val count get() = vertAccel.size
}

/** Sun position relative to the world (and optionally the vehicle) for glare geometry. */
data class SunGeometry(
    val elevationDeg: Double,    // negative below horizon
    val azimuthDeg: Double,      // 0=N, 90=E
)
