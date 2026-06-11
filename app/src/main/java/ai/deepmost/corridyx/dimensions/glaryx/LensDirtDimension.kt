package ai.deepmost.corridyx.dimensions.glaryx

import ai.deepmost.corridyx.cv.Cv
import ai.deepmost.corridyx.dimensions.Dim
import ai.deepmost.corridyx.dimensions.Engine
import ai.deepmost.corridyx.dimensions.FrameContext
import ai.deepmost.corridyx.dimensions.MetricSample
import ai.deepmost.corridyx.dimensions.OddDimension
import timber.log.Timber
import kotlin.math.abs

/**
 * GLARYX head a — LENS DIRT / OCCLUSION (lens). score = 1 - dirtFraction (1 = clean lens).
 *
 * Maintains a temporal per-block persistence map over an [cols]x[rows] Laplacian-variance grid.
 * Key idea: a block that stays low-sharpness WHILE the rest of the scene is changing is lens
 * contamination, not just a flat sky. So persistence only accrues on scene-changing frames:
 *   sceneChange = mean over blocks of |sharpness - prevSharpness| / (mean+eps)  (must exceed eps)
 *   for each block: if (sharpness < ABS_BLUR and sharpness < 0.25*medianSharpness) and sceneChanging
 *                     persistence += 0.08  else  persistence -= 0.05   (clamped 0..1)
 *   dirtFraction = #{blocks persistence > 0.6} / blocksTotal
 *
 * Exposes [lastDirtMask] for the DRIVE overlay and drives the "CLEAN LENS" driver alert.
 */
class LensDirtDimension : OddDimension {
    override val id = Dim.LENS

    val cols = 8
    val rows = 6
    private val persistence = FloatArray(cols * rows)
    private var prevGrid: FloatArray? = null

    /** Latest persistent-dirt block mask (row-major cols*rows) for UI overlay. Read on UI thread; volatile array swap. */
    @Volatile var lastDirtMask: BooleanArray = BooleanArray(cols * rows)
        private set

    override fun process(ctx: FrameContext): MetricSample? {
        return try {
            val grid = Cv.blockSharpnessGrid(ctx.frame, cols, rows)
            val prev = prevGrid
            // scene-change gate
            var sceneChange = 0f
            if (prev != null) {
                var s = 0f; var m = 0f
                for (i in grid.indices) { s += abs(grid[i] - prev[i]); m += grid[i] }
                sceneChange = if (m > 1e-3f) s / (m + 1e-3f) else 0f
            }
            prevGrid = grid

            // median sharpness
            val sorted = grid.clone(); sorted.sort()
            val median = sorted[sorted.size / 2]
            val sceneChanging = sceneChange > 0.04f

            val mask = BooleanArray(grid.size)
            var dirtyBlocks = 0
            for (i in grid.indices) {
                val low = grid[i] < ABS_BLUR && grid[i] < 0.25f * median
                if (low && sceneChanging) persistence[i] = (persistence[i] + 0.08f).coerceAtMost(1f)
                else persistence[i] = (persistence[i] - 0.05f).coerceAtLeast(0f)
                if (persistence[i] > 0.6f) { mask[i] = true; dirtyBlocks++ }
            }
            lastDirtMask = mask
            val dirtFraction = dirtyBlocks.toFloat() / grid.size

            MetricSample(
                dimension = id,
                score = (1f - dirtFraction).coerceIn(0f, 1f),
                raw = mapOf(
                    "dirtFraction" to dirtFraction,
                    "dirtyBlocks" to dirtyBlocks.toFloat(),
                    "sceneChange" to sceneChange,
                    "medianSharp" to median,
                ),
                engine = Engine.CLASSICAL,
                timestampNs = ctx.timestampNs,
            )
        } catch (t: Throwable) {
            Timber.w(t, "lens head failed")
            MetricSample(id, 1f, mapOf("error" to 1f), Engine.FAILED, ctx.timestampNs, locallyValid = false)
        }
    }

    override fun close() {
        persistence.fill(0f); prevGrid = null; lastDirtMask = BooleanArray(cols * rows)
    }

    private companion object { const val ABS_BLUR = 8f }
}
