package ai.deepmost.corridyx.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import timber.log.Timber
import kotlin.math.sqrt

/**
 * Fused location fixes + GnssStatus (and opportunistic raw GnssMeasurements) for the gnss_q head.
 * Degrades gracefully: no raw-measurement support -> STATUS_ONLY recorded; no GNSS at all -> NONE.
 * Permission-guarded; never throws on a missing grant.
 */
class LocationHub(private val context: Context) {

    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile var latestLocation: LocationSnapshot? = null
        private set
    @Volatile var latestGnss: GnssSnapshot? = null
        private set
    @Volatile var capability: GnssCapability = GnssCapability.NONE
        private set

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            latestLocation = LocationSnapshot(
                lat = loc.latitude, lon = loc.longitude,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else 99f,
                speedMps = if (loc.hasSpeed()) loc.speed else 0f,
                bearingDeg = if (loc.hasBearing()) loc.bearing else 0f,
                hasBearing = loc.hasBearing() && (loc.hasSpeed() && loc.speed > 0.5f),
                timestampMs = System.currentTimeMillis(),
            )
        }
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val n = status.satelliteCount
            var inView = 0; var used = 0
            var cn0Sum = 0f; var cn0Count = 0
            val cn0Used = ArrayList<Float>()
            val constellations = HashSet<Int>()
            for (i in 0 until n) {
                inView++
                val cn0 = status.getCn0DbHz(i)
                cn0Sum += cn0; cn0Count++
                if (status.usedInFix(i)) {
                    used++
                    cn0Used.add(cn0)
                    constellations.add(status.getConstellationType(i))
                }
            }
            val meanCn0 = if (cn0Used.isNotEmpty()) cn0Used.average().toFloat()
                else if (cn0Count > 0) cn0Sum / cn0Count else 0f
            val sortedDesc = cn0Used.sortedDescending()
            val topQ = if (sortedDesc.isNotEmpty()) {
                val q = (sortedDesc.size / 4).coerceAtLeast(1)
                sortedDesc.take(q).average().toFloat()
            } else meanCn0
            val variance = if (cn0Used.size >= 2) {
                val m = cn0Used.average().toFloat()
                (cn0Used.sumOf { ((it - m) * (it - m)).toDouble() } / cn0Used.size).toFloat()
            } else 0f
            if (capability == GnssCapability.NONE) capability = GnssCapability.STATUS_ONLY
            latestGnss = GnssSnapshot(
                satellitesInView = inView,
                satellitesUsedInFix = used,
                meanCn0 = meanCn0,
                topQuartileCn0 = topQ,
                constellations = constellations,
                cn0Variance = variance,
                pseudorangeRateJitter = latestGnss?.pseudorangeRateJitter ?: 0f,
                capability = capability,
                timestampMs = System.currentTimeMillis(),
            )
        }
    }

    private val measurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            capability = GnssCapability.RAW_MEASUREMENTS
            val uncertainties = event.measurements.map { it.pseudorangeRateUncertaintyMetersPerSecond.toFloat() }
            val jitter = if (uncertainties.isNotEmpty()) {
                val m = uncertainties.average().toFloat()
                sqrt(uncertainties.sumOf { ((it - m) * (it - m)).toDouble() } / uncertainties.size).toFloat() + m
            } else 0f
            latestGnss = latestGnss?.copy(pseudorangeRateJitter = jitter, capability = capability)
        }
    }

    fun start() {
        if (!hasLocationPermission()) { Timber.w("LocationHub: no fine-location permission"); return }
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .build()
            fused.requestLocationUpdates(req, locationCallback, context.mainLooper)
            lm.registerGnssStatusCallback(gnssStatusCallback, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { lm.registerGnssMeasurementsCallback(measurementsCallback) }
                    .onFailure { Timber.i("Raw GNSS measurements unsupported -> STATUS_ONLY") }
            }
            Timber.i("LocationHub started")
        } catch (se: SecurityException) {
            Timber.w(se, "LocationHub start denied")
        }
    }

    fun stop() {
        runCatching { fused.removeLocationUpdates(locationCallback) }
        runCatching { lm.unregisterGnssStatusCallback(gnssStatusCallback) }
        runCatching { lm.unregisterGnssMeasurementsCallback(measurementsCallback) }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
