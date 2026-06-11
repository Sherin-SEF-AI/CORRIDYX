package ai.deepmost.corridyx.dimensions.chaos

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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * SCENE COMPLEXITY / AGENT CHAOS (chaos). High chaos = harder ODD, so the subscore is INVERTED:
 * score = 1 - chaosLevel (1 = calm/easy corridor).
 *
 * With a registered DETECTOR model (COCO-mapped classes):
 *   agentCount, classEntropy (Shannon, normalised), vulnerableFrac (person/two-wheeler/animal),
 *   churn = (appeared + disappeared) / (prevN + curN) via greedy IoU>0.4 matching to last frame.
 *   chaosLevel = 0.40*clamp(agentCount/15) + 0.25*entropyNorm + 0.20*vulnerableFrac + 0.15*churn
 *
 * Classical fallback (no detector): motion-region count via frame differencing as an instability
 * proxy; chaosLevel = clamp(motionRegions / 12). Engine labelled "classical-motion".
 */
class ChaosDimension(private val registry: ModelRegistry) : OddDimension {
    override val id = Dim.CHAOS

    private data class Det(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val cls: String, val vulnerable: Boolean)
    private var prev: List<Det> = emptyList()

    override fun process(ctx: FrameContext): MetricSample? {
        return try {
            val engine = registry.engineFor(id)
            if (engine != null && engine.entry.kind == ModelKind.DETECTOR) {
                detectorScore(ctx, engine)
            } else {
                motionScore(ctx)
            }
        } catch (t: Throwable) {
            Timber.w(t, "chaos head failed")
            MetricSample(id, 0f, mapOf("error" to 1f), Engine.FAILED, ctx.timestampNs, locallyValid = false)
        }
    }

    private fun motionScore(ctx: FrameContext): MetricSample? {
        val prevFrame = ctx.prevFrame ?: return null // need two frames to difference
        val regions = Cv.motionRegions(prevFrame, ctx.frame)
        val chaosLevel = (regions / 12f).coerceIn(0f, 1f)
        return MetricSample(
            dimension = id,
            score = (1f - chaosLevel).coerceIn(0f, 1f),
            raw = mapOf("motionRegions" to regions.toFloat(), "chaosLevel" to chaosLevel),
            engine = "classical-motion",
            timestampNs = ctx.timestampNs,
        )
    }

    private fun detectorScore(ctx: FrameContext, engine: ai.deepmost.corridyx.registry.LiteRtEngine): MetricSample {
        val entry = engine.entry
        val input = TensorPrep.allocInput(entry)
        TensorPrep.fill(input, ctx.frame, Roi.full(ctx.frame), entry)
        // Classic TFLite SSD outputs: locations[1,N,4], classes[1,N], scores[1,N], count[1]
        val n = 10
        val locations = Array(1) { Array(n) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(n) }
        val scores = Array(1) { FloatArray(n) }
        val count = FloatArray(1)
        engine.run(input, mapOf(0 to locations, 1 to classes, 2 to scores, 3 to count))

        val dets = ArrayList<Det>()
        val classHist = HashMap<String, Int>()
        var vulnerable = 0
        val num = min(n, count[0].toInt().coerceIn(0, n))
        for (i in 0 until num) {
            if (scores[0][i] < 0.5f) continue
            val labelIdx = classes[0][i].toInt()
            val label = entry.labels.getOrNull(labelIdx) ?: "obj"
            val mapped = mapClass(label)
            val isVuln = mapped == "person" || mapped == "two_wheeler" || mapped == "animal"
            if (isVuln) vulnerable++
            classHist[mapped] = (classHist[mapped] ?: 0) + 1
            val loc = locations[0][i] // [ymin, xmin, ymax, xmax] normalised
            dets.add(Det(loc[1], loc[0], loc[3], loc[2], mapped, isVuln))
        }

        val agentCount = dets.size
        val entropyNorm = if (agentCount > 0) {
            var h = 0.0
            for ((_, c) in classHist) { val p = c.toDouble() / agentCount; h -= p * ln(p) }
            (h / ln(max(2.0, classHist.size.toDouble()))).toFloat().coerceIn(0f, 1f)
        } else 0f
        val vulnerableFrac = if (agentCount > 0) vulnerable.toFloat() / agentCount else 0f
        val churn = churn(prev, dets)
        prev = dets

        val chaosLevel = (0.40f * (agentCount / 15f).coerceIn(0f, 1f) +
            0.25f * entropyNorm + 0.20f * vulnerableFrac + 0.15f * churn).coerceIn(0f, 1f)

        return MetricSample(
            dimension = id,
            score = (1f - chaosLevel).coerceIn(0f, 1f),
            raw = mapOf(
                "agentCount" to agentCount.toFloat(),
                "classEntropy" to entropyNorm,
                "vulnerableFrac" to vulnerableFrac,
                "churn" to churn,
                "chaosLevel" to chaosLevel,
            ),
            engine = entry.engineTag,
            timestampNs = ctx.timestampNs,
        )
    }

    /** Greedy IoU>0.4 matching; churn = unmatched fraction across both frames. */
    private fun churn(a: List<Det>, b: List<Det>): Float {
        if (a.isEmpty() && b.isEmpty()) return 0f
        val matchedB = BooleanArray(b.size)
        var matched = 0
        for (da in a) {
            var bestJ = -1; var bestIou = 0.4f
            for (j in b.indices) {
                if (matchedB[j]) continue
                val iou = iou(da, b[j])
                if (iou > bestIou) { bestIou = iou; bestJ = j }
            }
            if (bestJ >= 0) { matchedB[bestJ] = true; matched++ }
        }
        val appeared = b.size - matched
        val disappeared = a.size - matched
        return ((appeared + disappeared).toFloat() / (a.size + b.size)).coerceIn(0f, 1f)
    }

    private fun iou(p: Det, q: Det): Float {
        val ix0 = max(p.x0, q.x0); val iy0 = max(p.y0, q.y0)
        val ix1 = min(p.x1, q.x1); val iy1 = min(p.y1, q.y1)
        val iw = max(0f, ix1 - ix0); val ih = max(0f, iy1 - iy0)
        val inter = iw * ih
        val ap = max(0f, p.x1 - p.x0) * max(0f, p.y1 - p.y0)
        val aq = max(0f, q.x1 - q.x0) * max(0f, q.y1 - q.y0)
        val u = ap + aq - inter
        return if (u <= 0f) 0f else inter / u
    }

    private fun mapClass(coco: String): String = when (coco.lowercase()) {
        "person" -> "person"
        "bicycle", "motorcycle", "motorbike" -> "two_wheeler"
        "car" -> "car"
        "bus", "truck", "train" -> "heavy"
        "cow", "dog", "cat", "horse", "sheep", "elephant", "bird" -> "animal"
        // auto-rickshaw is not a COCO class; closest proxy is small vehicle
        "auto", "rickshaw" -> "auto"
        else -> "other"
    }

    override fun close() { prev = emptyList() }
}
