package ai.deepmost.corridyx.dimensions.gnssq

import ai.deepmost.corridyx.capture.GnssCapability
import ai.deepmost.corridyx.dimensions.Dim
import ai.deepmost.corridyx.dimensions.Engine
import ai.deepmost.corridyx.dimensions.FrameContext
import ai.deepmost.corridyx.dimensions.MetricSample
import ai.deepmost.corridyx.dimensions.OddDimension
import timber.log.Timber

/**
 * GNSS QUALITY (gnss_q). Higher = better positioning for AV/ADAS. Maps Bangalore urban-canyon
 * shadowing (flyovers, tech-park canyons). Reads [FrameContext.gnss] (+ location accuracy);
 * returns null (a recorded gap) when there is no GNSS snapshot at all.
 *
 * Submetrics (each 0..1) and blend:
 *   usedSat   = clamp(usedInFix / 12)
 *   cn0       = clamp((meanCn0 - 20) / 25)            // 20..45 dB-Hz useful band
 *   topCn0    = clamp((topQuartileCn0 - 25) / 23)     // 25..48 dB-Hz
 *   diversity = clamp(constellationCount / 4)         // GPS/GLONASS/Galileo/BeiDou(+NavIC)
 *   accuracy  = 1 - clamp(accuracyM / 40)             // FusedLocation horizontal accuracy
 *   multipath = clamp(cn0Variance / 25) blended with clamp(prRateJitter / 5) where raw supported
 *   score = 0.25*usedSat + 0.20*cn0 + 0.15*topCn0 + 0.15*diversity + 0.15*accuracy + 0.10*(1 - multipath)
 *
 * Capability is recorded so STATUS_ONLY vs RAW_MEASUREMENTS sessions can be weighted by aggregation.
 */
class GnssQDimension : OddDimension {
    override val id = Dim.GNSS_Q

    override fun process(ctx: FrameContext): MetricSample? {
        val g = ctx.gnss ?: return null
        return try {
            val usedSat = (g.satellitesUsedInFix / 12f).coerceIn(0f, 1f)
            val cn0 = ((g.meanCn0 - 20f) / 25f).coerceIn(0f, 1f)
            val topCn0 = ((g.topQuartileCn0 - 25f) / 23f).coerceIn(0f, 1f)
            val diversity = (g.constellations.size / 4f).coerceIn(0f, 1f)
            val accM = ctx.location?.accuracyM ?: 40f
            val accuracy = (1f - (accM / 40f).coerceIn(0f, 1f))
            val mpVar = (g.cn0Variance / 25f).coerceIn(0f, 1f)
            val mpJit = if (g.capability == GnssCapability.RAW_MEASUREMENTS)
                (g.pseudorangeRateJitter / 5f).coerceIn(0f, 1f) else mpVar
            val multipath = (0.5f * mpVar + 0.5f * mpJit).coerceIn(0f, 1f)

            val score = (0.25f * usedSat + 0.20f * cn0 + 0.15f * topCn0 +
                0.15f * diversity + 0.15f * accuracy + 0.10f * (1f - multipath)).coerceIn(0f, 1f)

            MetricSample(
                dimension = id,
                score = score,
                raw = mapOf(
                    "usedInFix" to g.satellitesUsedInFix.toFloat(),
                    "inView" to g.satellitesInView.toFloat(),
                    "meanCn0" to g.meanCn0,
                    "topQuartileCn0" to g.topQuartileCn0,
                    "constellations" to g.constellations.size.toFloat(),
                    "accuracyM" to accM,
                    "cn0Variance" to g.cn0Variance,
                    "prRateJitter" to g.pseudorangeRateJitter,
                    "multipath" to multipath,
                    "capability" to when (g.capability) {
                        GnssCapability.RAW_MEASUREMENTS -> 2f
                        GnssCapability.STATUS_ONLY -> 1f
                        GnssCapability.NONE -> 0f
                    },
                ),
                engine = Engine.CLASSICAL,
                timestampNs = ctx.timestampNs,
            )
        } catch (t: Throwable) {
            Timber.w(t, "gnss_q head failed")
            MetricSample(id, 0f, mapOf("error" to 1f), Engine.FAILED, ctx.timestampNs, locallyValid = false)
        }
    }
}
