package ai.deepmost.corridyx.dimensions.lanevis

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
import kotlin.math.max

/**
 * LANE-MARKING VISIBILITY (lane_vis). Higher score = markings clearly visible.
 *
 * Classical method (day-one), over the lower-half road ROI:
 *   density   = clamp(markingCoverage / 0.05, 0..1)         // white/yellow HSV-gated pixel density
 *   structure = clamp(houghLineCount / 4, 0..1) * 0.6 + houghConfidence * 0.4  // near-vertical line voting
 *   contrast  = clamp((markMeanLuma - roadMeanLuma) / 80, 0..1)               // marking vs road surface
 *   score     = 0.40*density + 0.40*structure + 0.20*contrast
 *
 * Model upgrade (drop-in): if a SEGMENTATION model is registered for lane_vis, the score becomes
 *   score = 0.6*maskDensity + 0.4*maskMeanConfidence
 * and the engine tag becomes "<modelId>@<version>".
 */
class LaneVisDimension(private val registry: ModelRegistry) : OddDimension {
    override val id = Dim.LANE_VIS

    override fun process(ctx: FrameContext): MetricSample? {
        return try {
            val engine = registry.engineFor(id)
            if (engine != null && engine.entry.kind == ModelKind.SEGMENTATION) {
                modelScore(ctx, engine) ?: classicalScore(ctx)
            } else {
                classicalScore(ctx)
            }
        } catch (t: Throwable) {
            Timber.w(t, "lane_vis head failed")
            MetricSample(id, 0f, mapOf("error" to 1f), Engine.FAILED, ctx.timestampNs, locallyValid = false)
        }
    }

    private fun classicalScore(ctx: FrameContext): MetricSample {
        val f = ctx.frame
        val roi = Roi.lowerHalf(f, topFrac = 0.5f)
        val mm = Cv.markingMask(f, roi)
        val hough = Cv.houghVertical(f, roi, mm.mask)

        val density = (mm.coverage / 0.05f).coerceIn(0f, 1f)
        val structure = ((hough.lineCount / 4f).coerceIn(0f, 1f) * 0.6f + hough.confidence * 0.4f).coerceIn(0f, 1f)
        val contrast = (max(0f, mm.markMeanLuma - mm.roadMeanLuma) / 80f).coerceIn(0f, 1f)
        val score = (0.40f * density + 0.40f * structure + 0.20f * contrast).coerceIn(0f, 1f)

        return MetricSample(
            dimension = id,
            score = score,
            raw = mapOf(
                "coverage" to mm.coverage,
                "density" to density,
                "lineCount" to hough.lineCount.toFloat(),
                "houghConf" to hough.confidence,
                "markLuma" to mm.markMeanLuma,
                "roadLuma" to mm.roadMeanLuma,
                "contrast" to contrast,
            ),
            engine = Engine.CLASSICAL,
            timestampNs = ctx.timestampNs,
        )
    }

    private fun modelScore(ctx: FrameContext, engine: ai.deepmost.corridyx.registry.LiteRtEngine): MetricSample? {
        val entry = engine.entry
        val roi = Roi.lowerHalf(ctx.frame, topFrac = 0.45f)
        val input = TensorPrep.allocInput(entry)
        TensorPrep.fill(input, ctx.frame, roi, entry)
        // Expect output [1, H, W, 1] float mask probabilities.
        val out = Array(1) { Array(entry.inputHeight) { Array(entry.inputWidth) { FloatArray(1) } } }
        engine.runSingle(input, out)
        var hit = 0; var confSum = 0.0; val total = entry.inputHeight * entry.inputWidth
        for (y in 0 until entry.inputHeight) for (x in 0 until entry.inputWidth) {
            val p = out[0][y][x][0]
            if (p >= 0.5f) { hit++; confSum += p }
        }
        val density = (hit.toFloat() / total / 0.12f).coerceIn(0f, 1f) // ~12% mask is strong markings
        val meanConf = if (hit > 0) (confSum / hit).toFloat() else 0f
        val score = (0.6f * density + 0.4f * meanConf).coerceIn(0f, 1f)
        return MetricSample(
            dimension = id,
            score = score,
            raw = mapOf("maskDensity" to density, "maskConf" to meanConf, "maskHitFrac" to hit.toFloat() / total),
            engine = entry.engineTag,
            timestampNs = ctx.timestampNs,
        )
    }
}
