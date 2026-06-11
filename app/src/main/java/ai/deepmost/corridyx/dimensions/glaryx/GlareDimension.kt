package ai.deepmost.corridyx.dimensions.glaryx

import ai.deepmost.corridyx.cv.Blob
import ai.deepmost.corridyx.cv.Cv
import ai.deepmost.corridyx.dimensions.Dim
import ai.deepmost.corridyx.dimensions.Engine
import ai.deepmost.corridyx.dimensions.FrameContext
import ai.deepmost.corridyx.dimensions.MetricSample
import ai.deepmost.corridyx.dimensions.OddDimension
import timber.log.Timber
import kotlin.math.abs

/**
 * GLARYX head b — GLARE / LOW-SUN BLINDING (glare). score = 1 - glareSeverity.
 *
 * Saturated-highlight blob analysis (Cv.brightBlobs at luma>=245):
 *   brightFrac     = total bright-blob area / frame area
 *   largestFrac    = largest blob area / frame area
 *   satSeverity    = clamp(brightFrac / 0.06) * 0.6 + clamp(largestFrac / 0.03) * 0.4
 *
 * Sun-geometry cross-check (computed sun az/el vs vehicle heading), flags geometric low-sun-into-
 * camera BEFORE saturation:
 *   geomFlag = sunElev in (0,18) deg AND |sunAz - heading| < 35 deg
 *   geomSeverity = geomFlag ? clamp((18 - sunElev)/18) * cos(relAz) : 0
 *   glareSeverity = max(satSeverity, geomSeverity)
 *
 * Exposes [lastBlobs] for the DRIVE overlay; drives the "CAMERA BLINDED — sun" alert.
 */
class GlareDimension : OddDimension {
    override val id = Dim.GLARE

    @Volatile var lastBlobs: List<Blob> = emptyList()
        private set
    @Volatile var lastFrameW = 0
    @Volatile var lastFrameH = 0

    override fun process(ctx: FrameContext): MetricSample? {
        return try {
            val f = ctx.frame
            val area = (f.width * f.height).toFloat()
            val blobs = Cv.brightBlobs(f, threshold = 245, ds = 2, minSize = 16)
            lastBlobs = blobs; lastFrameW = f.width; lastFrameH = f.height

            var brightArea = 0; var largest = 0
            for (bl in blobs) { brightArea += bl.size; if (bl.size > largest) largest = bl.size }
            val brightFrac = brightArea / area
            val largestFrac = largest / area
            val satSeverity = ((brightFrac / 0.06f).coerceIn(0f, 1f) * 0.6f +
                (largestFrac / 0.03f).coerceIn(0f, 1f) * 0.4f).coerceIn(0f, 1f)

            // sun geometry
            var geomSeverity = 0f
            var geomFlag = false
            var relAz = -1f
            val sun = ctx.sun; val heading = ctx.headingDeg
            if (sun != null && heading != null && sun.elevationDeg in 0.0..18.0) {
                var d = abs(sun.azimuthDeg - heading).toFloat()
                if (d > 180f) d = 360f - d
                relAz = d
                if (d < 35f) {
                    geomFlag = true
                    val elevTerm = ((18.0 - sun.elevationDeg) / 18.0).toFloat().coerceIn(0f, 1f)
                    val azTerm = kotlin.math.cos(Math.toRadians(d.toDouble())).toFloat().coerceIn(0f, 1f)
                    geomSeverity = (elevTerm * azTerm).coerceIn(0f, 1f)
                }
            }

            val glareSeverity = maxOf(satSeverity, geomSeverity)

            MetricSample(
                dimension = id,
                score = (1f - glareSeverity).coerceIn(0f, 1f),
                raw = mapOf(
                    "severity" to glareSeverity,
                    "satSeverity" to satSeverity,
                    "geomSeverity" to geomSeverity,
                    "geomFlag" to if (geomFlag) 1f else 0f,
                    "brightFrac" to brightFrac,
                    "largestFrac" to largestFrac,
                    "blobCount" to blobs.size.toFloat(),
                    "sunElev" to (sun?.elevationDeg?.toFloat() ?: -99f),
                    "sunRelAz" to relAz,
                ),
                engine = Engine.CLASSICAL,
                timestampNs = ctx.timestampNs,
            )
        } catch (t: Throwable) {
            Timber.w(t, "glare head failed")
            MetricSample(id, 1f, mapOf("error" to 1f), Engine.FAILED, ctx.timestampNs, locallyValid = false)
        }
    }

    override fun close() { lastBlobs = emptyList() }
}
