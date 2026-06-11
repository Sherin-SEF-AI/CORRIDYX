package ai.deepmost.corridyx.dimensions.glaryx

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
 * GLARYX head d — NIGHT QUALITY (nightq). Applies ONLY in the NIGHT bucket (returns null otherwise).
 * score = night seeing quality (1 = clean, well-lit night image).
 *
 *   noise        = Cv.flatRegionNoise (high-ISO grain in flat blocks)
 *   blurProxy    = speed-gated motion blur: 1 - clamp(edgeEnergy/0.10), only counted when speed>3 m/s
 *   headlightOnly= luma falloff profile: bottom-centre bright, top dark, overall dim
 *                  falloffRatio = bottomCentreMean / (topMean+eps); flag when ratio>2.2 AND globalMean<55
 *   score = clamp(1 - (0.5*noise + 0.3*blurProxy + 0.2*headlightOnlySeverity))
 */
class NightQDimension : OddDimension {
    override val id = Dim.NIGHTQ

    override fun process(ctx: FrameContext): MetricSample? {
        if (ctx.timeBucket != TimeBucket.NIGHT) return null
        return try {
            val f = ctx.frame
            val full = Roi.full(f)
            val noise = Cv.flatRegionNoise(f, full)

            val grad = Cv.sobel(f, full, bins = 4, stride = 2)
            val speed = ctx.location?.speedMps ?: 0f
            val blurProxy = if (speed > 3f) (1f - (grad.edgeEnergy / 0.10f).coerceIn(0f, 1f)) else 0f

            // headlight-only falloff
            val bottomCentre = Roi((f.width * 0.30f).toInt(), (f.height * 0.6f).toInt(),
                (f.width * 0.70f).toInt(), f.height)
            val top = Roi(0, 0, f.width, (f.height * 0.30f).toInt())
            val bcMean = Cv.lumaStats(f, bottomCentre, stride = 2).mean
            val topMean = Cv.lumaStats(f, top, stride = 2).mean
            val globalMean = Cv.lumaStats(f, full, stride = 4).mean
            val falloffRatio = bcMean / (topMean + 1e-3f)
            val headlightOnly = falloffRatio > 2.2f && globalMean < 55f
            val headlightSeverity = if (headlightOnly)
                ((falloffRatio - 2.2f) / 2.0f).coerceIn(0f, 1f) else 0f

            val score = (1f - (0.5f * noise + 0.3f * blurProxy + 0.2f * headlightSeverity)).coerceIn(0f, 1f)
            MetricSample(
                dimension = id,
                score = score,
                raw = mapOf(
                    "noise" to noise,
                    "blurProxy" to blurProxy,
                    "headlightOnly" to if (headlightOnly) 1f else 0f,
                    "falloffRatio" to falloffRatio,
                    "globalMean" to globalMean,
                    "nightSeeing" to score,
                ),
                engine = Engine.CLASSICAL,
                timestampNs = ctx.timestampNs,
            )
        } catch (t: Throwable) {
            Timber.w(t, "nightq head failed")
            MetricSample(id, 1f, mapOf("error" to 1f), Engine.FAILED, ctx.timestampNs, locallyValid = false)
        }
    }
}
