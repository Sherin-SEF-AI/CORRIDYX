package ai.deepmost.corridyx.dimensions.illum

import ai.deepmost.corridyx.capture.TimeBucket
import ai.deepmost.corridyx.cv.Cv
import ai.deepmost.corridyx.cv.Roi
import ai.deepmost.corridyx.dimensions.Dim
import ai.deepmost.corridyx.dimensions.Engine
import ai.deepmost.corridyx.dimensions.FrameContext
import ai.deepmost.corridyx.dimensions.MetricSample
import ai.deepmost.corridyx.dimensions.OddDimension
import timber.log.Timber

/**
 * ILLUMINATION (illum). Scored RELATIVE to the time-of-day bucket; the decision-relevant fact is
 * "how well-lit is this corridor for a camera, given day/dusk/night".
 *
 *   exposure       = (1 - clipLowFrac) * (1 - 0.5*clipHighFrac)   // not crushed / not blown
 *   brightAdequacy = clamp(mean / target) where target = 50 (DAY/DUSK) or 85 (NIGHT street-lighting)
 *   banding        = Cv.rowBanding (streetlight flicker / rolling-shutter)
 *   lightAgree     = 1 - |normLux - normLuma| cross-check when a light sensor is present (else 1)
 *   score          = clamp((0.6*brightAdequacy + 0.4*exposure) * (1 - 0.5*banding) * lightAgree)
 *
 * For NIGHT this directly quantifies street-lighting quality — one of the most decision-relevant
 * ODD facts for camera-based autonomy.
 */
class IllumDimension : OddDimension {
    override val id = Dim.ILLUM

    override fun process(ctx: FrameContext): MetricSample? {
        return try {
            val f = ctx.frame
            val roi = Roi.full(f)
            val s = Cv.lumaStats(f, roi, stride = 2)
            val banding = Cv.rowBanding(f, roi)

            val exposure = ((1f - s.clipLowFrac) * (1f - 0.5f * s.clipHighFrac)).coerceIn(0f, 1f)
            val target = if (ctx.timeBucket == TimeBucket.NIGHT) 85f else 50f
            val brightAdequacy = (s.mean / target).coerceIn(0f, 1f)

            val lux = ctx.lightLux
            val lightAgree = if (lux != null) {
                // crude monotone cross-check: both mapped to 0..1 (lux log-scaled to ~0..1 over 0..1000)
                val normLux = (Math.log10((lux + 1.0)) / 3.0).coerceIn(0.0, 1.0).toFloat()
                val normLuma = (s.mean / 255f)
                (1f - kotlin.math.abs(normLux - normLuma)).coerceIn(0.4f, 1f) // never fully zero out
            } else 1f

            val score = ((0.6f * brightAdequacy + 0.4f * exposure) * (1f - 0.5f * banding) * lightAgree)
                .coerceIn(0f, 1f)

            MetricSample(
                dimension = id,
                score = score,
                raw = mapOf(
                    "mean" to s.mean,
                    "clipLow" to s.clipLowFrac,
                    "clipHigh" to s.clipHighFrac,
                    "p95" to s.p95.toFloat(),
                    "banding" to banding,
                    "lux" to (lux ?: -1f),
                    "brightAdequacy" to brightAdequacy,
                    "exposure" to exposure,
                    "bucket" to ctx.timeBucket.ordinal.toFloat(),
                ),
                engine = Engine.CLASSICAL,
                timestampNs = ctx.timestampNs,
            )
        } catch (t: Throwable) {
            Timber.w(t, "illum head failed")
            MetricSample(id, 0f, mapOf("error" to 1f), Engine.FAILED, ctx.timestampNs, locallyValid = false)
        }
    }
}
