package ai.deepmost.corridyx.dimensions.rough

import ai.deepmost.corridyx.dimensions.Dim
import ai.deepmost.corridyx.dimensions.Engine
import ai.deepmost.corridyx.dimensions.FrameContext
import ai.deepmost.corridyx.dimensions.MetricSample
import ai.deepmost.corridyx.dimensions.OddDimension
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ROAD ROUGHNESS (rough). IMU-primary; smoother road = higher score. Gated below [minSpeedMps]
 * (returns null = a recorded gap; never fabricates roughness from a parked vehicle).
 *
 * Over the IMU window (gravity-removed vertical acceleration, ~100 Hz):
 *   detrended      = a - mean(a)
 *   band           = detrended - movingAvg(detrended, 7)        // ~band-pass, keeps mid/high road energy
 *   rmsBand        = RMS(band)                                  // m/s^2
 *   spikeCount     = #{|detrended| > 3.5}                       // pothole proxy
 *   roughnessIndex = rmsBand * sqrt(REF_SPEED / speed)          // speed-normalised to REF_SPEED=10 m/s
 *   roughNorm      = clamp(roughnessIndex / 3.5)
 *   score          = 1 - roughNorm
 *
 * Mount-vibration sanity check (flags contaminated segments; consumed by Fusion gate):
 *   hfAccel    = RMS(secondDiff(a))           // high-freq accel energy
 *   hfGyro     = RMS(diff(gyroMag))           // high-freq angular vibration
 *   mountContam= clamp(0.5*clamp(hfAccel/(rmsBand+eps) - 1) + 0.5*clamp(hfGyro/1.5))
 *
 * Speed is recorded so fleet aggregation can re-normalise across vehicles/speeds.
 */
class RoughnessDimension : OddDimension {
    override val id = Dim.ROUGH

    /** Updated by the pipeline whenever settings change (single-writer). */
    @Volatile var minSpeedMps: Float = 2.5f

    private companion object { const val REF_SPEED = 10f }

    override fun process(ctx: FrameContext): MetricSample? {
        val imu = ctx.imu ?: return null
        val speed = ctx.location?.speedMps ?: return null
        if (imu.count < 16 || speed < minSpeedMps) return null // gap, not a fabricated zero
        return try {
            val a = imu.vertAccel
            val mean = a.average().toFloat()
            val detr = FloatArray(a.size) { a[it] - mean }

            // moving-average band-pass
            val band = FloatArray(detr.size)
            val win = 7; val half = win / 2
            for (i in detr.indices) {
                var s = 0f; var c = 0
                var k = i - half
                while (k <= i + half) { if (k in detr.indices) { s += detr[k]; c++ }; k++ }
                band[i] = detr[i] - s / c
            }
            val rmsBand = rms(band)

            var spikeCount = 0
            for (v in detr) if (abs(v) > 3.5f) spikeCount++

            val roughnessIndex = rmsBand * sqrt(REF_SPEED / speed.coerceAtLeast(minSpeedMps))
            val roughNorm = (roughnessIndex / 3.5f).coerceIn(0f, 1f)
            val score = (1f - roughNorm).coerceIn(0f, 1f)

            // mount-resonance proxy
            val secondDiff = FloatArray(maxOf(0, a.size - 2)) { a[it + 2] - 2 * a[it + 1] + a[it] }
            val hfAccel = rms(secondDiff)
            val gyro = imu.gyroMag
            val gyroDiff = if (gyro.size >= 2) FloatArray(gyro.size - 1) { gyro[it + 1] - gyro[it] } else FloatArray(0)
            val hfGyro = rms(gyroDiff)
            val mountContam = (0.5f * ((hfAccel / (rmsBand + 1e-3f)) - 1f).coerceIn(0f, 1f) +
                0.5f * (hfGyro / 1.5f).coerceIn(0f, 1f)).coerceIn(0f, 1f)

            MetricSample(
                dimension = id,
                score = score,
                raw = mapOf(
                    "rmsBand" to rmsBand,
                    "roughnessIndex" to roughnessIndex,
                    "spikeCount" to spikeCount.toFloat(),
                    "speedMps" to speed,
                    "mountContam" to mountContam,
                    "sampleHz" to imu.sampleRateHz,
                    "nSamples" to imu.count.toFloat(),
                ),
                engine = Engine.CLASSICAL,
                timestampNs = ctx.timestampNs,
            )
        } catch (t: Throwable) {
            Timber.w(t, "rough head failed")
            MetricSample(id, 0f, mapOf("error" to 1f), Engine.FAILED, ctx.timestampNs, locallyValid = false)
        }
    }

    private fun rms(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return sqrt(s / x.size).toFloat()
    }
}
