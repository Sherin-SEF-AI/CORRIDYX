package ai.deepmost.corridyx.dimensions.glaryx

import ai.deepmost.corridyx.cv.Cv
import ai.deepmost.corridyx.cv.Roi
import ai.deepmost.corridyx.dimensions.Dim
import ai.deepmost.corridyx.dimensions.Engine
import ai.deepmost.corridyx.dimensions.FrameContext
import ai.deepmost.corridyx.dimensions.MetricSample
import ai.deepmost.corridyx.dimensions.OddDimension
import ai.deepmost.corridyx.registry.ModelKind
import ai.deepmost.corridyx.registry.ModelRegistry
import ai.deepmost.corridyx.registry.TensorPrep
import timber.log.Timber
import kotlin.math.sqrt

/**
 * GLARYX head c — RAIN / FOG / HAZE (atmo). score = 1 - max(rainSeverity, hazeSeverity).
 *
 * Fog/haze (visibility loss):
 *   darkChannel = Cv.darkChannelMean (rises under scattered light/fog)
 *   normStd     = lumaStd / (lumaMean+eps)                      // global contrast
 *   hazeSeverity= clamp(0.5*clamp((darkChannel-0.35)/0.40) + 0.5*clamp((0.18-normStd)/0.18))
 *   visibilityScore = 1 - hazeSeverity
 *
 * Rain streaks (transient directional high-freq texture + wiper periodicity):
 *   vertShare   = share of gradient-orientation energy within +/-20deg of vertical (upper ROI)
 *   streakIndex = clamp((vertShare - 0.30)/0.30) * clamp(edgeEnergy/0.12)
 *   wiperIndex  = temporal std of recent edge-energy / (mean+eps)  (ring buffer, ~3s)
 *   rainSeverity= clamp(0.7*streakIndex + 0.3*clamp(wiperIndex/0.5))
 *
 * Optional CLASSIFIER model upgrade (labels mapped to clear/rain/fog/haze) replaces the classical
 * severities; engine tag becomes the model tag.
 */
class AtmoDimension(private val registry: ModelRegistry) : OddDimension {
    override val id = Dim.ATMO

    private val edgeRing = FloatArray(16)
    private var ringIdx = 0
    private var ringFilled = 0

    override fun process(ctx: FrameContext): MetricSample? {
        return try {
            registry.engineFor(id)?.takeIf { it.entry.kind == ModelKind.CLASSIFIER }
                ?.let { return modelScore(ctx, it) ?: classicalScore(ctx) }
            classicalScore(ctx)
        } catch (t: Throwable) {
            Timber.w(t, "atmo head failed")
            MetricSample(id, 1f, mapOf("error" to 1f), Engine.FAILED, ctx.timestampNs, locallyValid = false)
        }
    }

    private fun classicalScore(ctx: FrameContext): MetricSample {
        val f = ctx.frame
        val full = Roi.full(f)

        // --- haze / fog ---
        val darkCh = Cv.darkChannelMean(f, patch = 16)
        val s = Cv.lumaStats(f, full, stride = 2)
        val normStd = s.std / (s.mean + 1e-3f)
        val hazeSeverity = (0.5f * ((darkCh - 0.35f) / 0.40f).coerceIn(0f, 1f) +
            0.5f * ((0.18f - normStd) / 0.18f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val visibilityScore = 1f - hazeSeverity

        // --- rain streaks (upper-third ROI: windshield + above-horizon) ---
        val upper = Roi(0, 0, f.width, f.height / 3)
        val grad = Cv.sobel(f, upper, bins = 18, stride = 1)
        // bins span 0..180deg; vertical edges => gradient horizontal => bins near 0/180.
        // rain streaks are near-vertical structures -> vertical gradient -> bins near 90deg index 9.
        val bins = grad.orientHist.size
        var vertShare = 0f
        val center = bins / 2
        for (i in (center - 2)..(center + 2)) if (i in 0 until bins) vertShare += grad.orientHist[i]
        val streakIndex = (((vertShare - 0.30f) / 0.30f).coerceIn(0f, 1f) *
            (grad.edgeEnergy / 0.12f).coerceIn(0f, 1f)).coerceIn(0f, 1f)

        // wiper periodicity from temporal edge-energy variance
        edgeRing[ringIdx] = grad.edgeEnergy
        ringIdx = (ringIdx + 1) % edgeRing.size
        if (ringFilled < edgeRing.size) ringFilled++
        val wiperIndex = ringStd() / (ringMean() + 1e-3f)
        val rainSeverity = (0.7f * streakIndex + 0.3f * (wiperIndex / 0.5f).coerceIn(0f, 1f)).coerceIn(0f, 1f)

        val score = (1f - maxOf(rainSeverity, hazeSeverity)).coerceIn(0f, 1f)
        return MetricSample(
            dimension = id,
            score = score,
            raw = mapOf(
                "rainSeverity" to rainSeverity,
                "hazeSeverity" to hazeSeverity,
                "visibility" to visibilityScore,
                "darkChannel" to darkCh,
                "normStd" to normStd,
                "vertShare" to vertShare,
                "streakIndex" to streakIndex,
                "wiperIndex" to wiperIndex,
            ),
            engine = Engine.CLASSICAL,
            timestampNs = ctx.timestampNs,
        )
    }

    private fun modelScore(ctx: FrameContext, engine: ai.deepmost.corridyx.registry.LiteRtEngine): MetricSample? {
        val entry = engine.entry
        val input = TensorPrep.allocInput(entry)
        TensorPrep.fill(input, ctx.frame, Roi.full(ctx.frame), entry)
        val nLabels = entry.labels.size.coerceAtLeast(1)
        val out = Array(1) { FloatArray(nLabels) }
        engine.runSingle(input, out)
        var rain = 0f; var fog = 0f; var haze = 0f
        for (i in 0 until nLabels) {
            when (entry.labels[i].lowercase()) {
                "rain" -> rain = out[0][i]
                "fog" -> fog = out[0][i]
                "haze", "mist" -> haze = out[0][i]
            }
        }
        val rainSeverity = rain.coerceIn(0f, 1f)
        val hazeSeverity = maxOf(fog, haze).coerceIn(0f, 1f)
        return MetricSample(
            dimension = id,
            score = (1f - maxOf(rainSeverity, hazeSeverity)).coerceIn(0f, 1f),
            raw = mapOf(
                "rainSeverity" to rainSeverity,
                "hazeSeverity" to hazeSeverity,
                "visibility" to (1f - hazeSeverity),
                "pRain" to rain, "pFog" to fog, "pHaze" to haze,
            ),
            engine = entry.engineTag,
            timestampNs = ctx.timestampNs,
        )
    }

    private fun ringMean(): Float {
        if (ringFilled == 0) return 0f
        var s = 0f; for (i in 0 until ringFilled) s += edgeRing[i]; return s / ringFilled
    }

    private fun ringStd(): Float {
        if (ringFilled < 2) return 0f
        val m = ringMean(); var s = 0f
        for (i in 0 until ringFilled) { val d = edgeRing[i] - m; s += d * d }
        return sqrt(s / ringFilled)
    }

    override fun close() { ringFilled = 0; ringIdx = 0 }
}
