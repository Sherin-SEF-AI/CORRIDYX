package ai.deepmost.corridyx.cv

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A rectangular region of interest in frame pixel coordinates, inclusive-exclusive. */
data class Roi(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val w get() = x1 - x0
    val h get() = y1 - y0
    companion object {
        fun full(f: AnalysisFrame) = Roi(0, 0, f.width, f.height)
        /** Lower-half road band used by the lane-marking head. */
        fun lowerHalf(f: AnalysisFrame, topFrac: Float = 0.5f) =
            Roi(0, (f.height * topFrac).toInt(), f.width, f.height)
    }
}

data class LumaStats(
    val mean: Float,
    val std: Float,
    val clipLowFrac: Float,   // fraction of pixels <= 16
    val clipHighFrac: Float,  // fraction of pixels >= 240
    val p50: Int,
    val p95: Int,
)

data class Blob(val size: Int, val cx: Float, val cy: Float, val x0: Int, val y0: Int, val x1: Int, val y1: Int)

/**
 * Pure-Kotlin classical computer vision over [AnalysisFrame]. Every routine is allocation-light
 * and bounded; heavy ones sub-sample with a stride so the whole head set fits in one analysis tick
 * on a mid-range arm64 phone. No OpenCV.
 */
object Cv {

    // ---------------------------------------------------------------------------------------------
    // Histogram + intensity statistics
    // ---------------------------------------------------------------------------------------------

    /** 256-bin luma histogram over [roi]. */
    fun histogram(f: AnalysisFrame, roi: Roi, stride: Int = 1): IntArray {
        val h = IntArray(256)
        var y = roi.y0
        while (y < roi.y1) {
            val row = y * f.width
            var x = roi.x0
            while (x < roi.x1) {
                h[f.luma[row + x].toInt() and 0xFF]++
                x += stride
            }
            y += stride
        }
        return h
    }

    fun lumaStats(f: AnalysisFrame, roi: Roi, stride: Int = 1): LumaStats {
        val hist = histogram(f, roi, stride)
        var n = 0L; var sum = 0L; var sumSq = 0L
        for (v in 0..255) {
            val c = hist[v]
            if (c == 0) continue
            n += c
            sum += v.toLong() * c
            sumSq += v.toLong() * v * c
        }
        if (n == 0L) return LumaStats(0f, 0f, 0f, 0f, 0, 0)
        val mean = sum.toFloat() / n
        val variance = (sumSq.toFloat() / n) - mean * mean
        val std = sqrt(max(0f, variance))
        var low = 0L; var high = 0L
        for (v in 0..16) low += hist[v]
        for (v in 240..255) high += hist[v]
        return LumaStats(
            mean = mean,
            std = std,
            clipLowFrac = low.toFloat() / n,
            clipHighFrac = high.toFloat() / n,
            p50 = percentile(hist, n, 0.50),
            p95 = percentile(hist, n, 0.95),
        )
    }

    private fun percentile(hist: IntArray, n: Long, p: Double): Int {
        val target = (n * p).toLong()
        var acc = 0L
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= target) return v
        }
        return 255
    }

    // ---------------------------------------------------------------------------------------------
    // Laplacian variance (sharpness / focus) — used for lens-dirt block grid & global blur
    // ---------------------------------------------------------------------------------------------

    /** 4-neighbour Laplacian variance over a region. Higher = sharper. */
    fun laplacianVariance(f: AnalysisFrame, roi: Roi): Float {
        val w = f.width
        var n = 0L; var sum = 0.0; var sumSq = 0.0
        var y = max(1, roi.y0)
        val yEnd = min(f.height - 1, roi.y1)
        val xEnd = min(w - 1, roi.x1)
        while (y < yEnd) {
            val row = y * w
            var x = max(1, roi.x0)
            while (x < xEnd) {
                val c = f.luma[row + x].toInt() and 0xFF
                val lap = (f.luma[row + x - 1].toInt() and 0xFF) +
                    (f.luma[row + x + 1].toInt() and 0xFF) +
                    (f.luma[row - w + x].toInt() and 0xFF) +
                    (f.luma[row + w + x].toInt() and 0xFF) - 4 * c
                sum += lap; sumSq += lap.toDouble() * lap; n++
                x++
            }
            y++
        }
        if (n < 2) return 0f
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat().coerceAtLeast(0f)
    }

    /** Per-block Laplacian variance grid (cols x rows), row-major. Drives the lens-dirt temporal map. */
    fun blockSharpnessGrid(f: AnalysisFrame, cols: Int, rows: Int): FloatArray {
        val out = FloatArray(cols * rows)
        val bw = f.width / cols
        val bh = f.height / rows
        for (ry in 0 until rows) {
            for (rx in 0 until cols) {
                val roi = Roi(rx * bw, ry * bh, (rx + 1) * bw, (ry + 1) * bh)
                out[ry * cols + rx] = laplacianVariance(f, roi)
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Sobel gradients — magnitude energy + orientation histogram (rain streaks, motion blur, edges)
    // ---------------------------------------------------------------------------------------------

    data class GradResult(
        val edgeEnergy: Float,          // mean gradient magnitude, normalised 0..1
        val orientHist: FloatArray,     // normalised orientation histogram (bins over 0..180deg)
        val horizEnergy: Float,         // |gx| share
        val vertEnergy: Float,          // |gy| share
    )

    /** Sobel gradient stats over [roi], orientation binned into [bins] over 0..180deg. */
    fun sobel(f: AnalysisFrame, roi: Roi, bins: Int = 18, stride: Int = 1): GradResult {
        val w = f.width
        val hist = FloatArray(bins)
        var magSum = 0.0; var n = 0L
        var gxAbs = 0.0; var gyAbs = 0.0
        var y = max(1, roi.y0)
        val yEnd = min(f.height - 1, roi.y1)
        val xEnd = min(w - 1, roi.x1)
        while (y < yEnd) {
            val row = y * w
            var x = max(1, roi.x0)
            while (x < xEnd) {
                val tl = f.luma[row - w + x - 1].toInt() and 0xFF
                val tc = f.luma[row - w + x].toInt() and 0xFF
                val tr = f.luma[row - w + x + 1].toInt() and 0xFF
                val ml = f.luma[row + x - 1].toInt() and 0xFF
                val mr = f.luma[row + x + 1].toInt() and 0xFF
                val bl = f.luma[row + w + x - 1].toInt() and 0xFF
                val bc = f.luma[row + w + x].toInt() and 0xFF
                val br = f.luma[row + w + x + 1].toInt() and 0xFF
                val gx = (tr + 2 * mr + br) - (tl + 2 * ml + bl)
                val gy = (bl + 2 * bc + br) - (tl + 2 * tc + tr)
                val mag = sqrt((gx * gx + gy * gy).toDouble())
                magSum += mag; n++
                gxAbs += abs(gx); gyAbs += abs(gy)
                if (mag > 32) {
                    // orientation of the edge (perpendicular to gradient): use gradient angle 0..180
                    var ang = Math.toDegrees(atan2(gy.toDouble(), gx.toDouble()))
                    if (ang < 0) ang += 180.0
                    if (ang >= 180.0) ang -= 180.0
                    val bin = (ang / 180.0 * bins).toInt().coerceIn(0, bins - 1)
                    hist[bin] += mag.toFloat()
                }
                x += stride
            }
            y += stride
        }
        // normalise
        var hs = 0f; for (v in hist) hs += v
        if (hs > 0f) for (i in hist.indices) hist[i] /= hs
        val total = (gxAbs + gyAbs).coerceAtLeast(1.0)
        val meanMag = if (n > 0) (magSum / n) else 0.0
        return GradResult(
            edgeEnergy = (meanMag / 255.0).toFloat().coerceIn(0f, 1f),
            orientHist = hist,
            horizEnergy = (gxAbs / total).toFloat(),
            vertEnergy = (gyAbs / total).toFloat(),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Constrained Hough line voting — lane-marking-like near-vertical structure
    // ---------------------------------------------------------------------------------------------

    data class HoughResult(val lineCount: Int, val confidence: Float)

    /**
     * Votes for near-vertical lines (lane markings in the lower ROI converge toward vertical).
     * Only edge pixels whose [mask] is true (or all strong edges if mask==null) vote. theta swept
     * over +/-50deg from vertical; returns count of accumulator peaks and their normalised strength.
     */
    fun houghVertical(
        f: AnalysisFrame,
        roi: Roi,
        mask: BooleanArray?,
        magThreshold: Int = 48,
        stride: Int = 2,
    ): HoughResult {
        val w = f.width
        // theta = angle of line normal from horizontal; near-vertical lines have normal near 0/180.
        // sweep normal angle in [40,140] deg => lines within +/-50deg of vertical.
        val thetaMin = 40; val thetaMax = 140; val thetaStep = 5
        val nTheta = (thetaMax - thetaMin) / thetaStep + 1
        val cosT = FloatArray(nTheta); val sinT = FloatArray(nTheta)
        for (i in 0 until nTheta) {
            val a = Math.toRadians((thetaMin + i * thetaStep).toDouble())
            cosT[i] = cos(a).toFloat(); sinT[i] = sin(a).toFloat()
        }
        val diag = sqrt((roi.w * roi.w + roi.h * roi.h).toDouble()).toInt() + 1
        val rhoBins = 2 * diag + 1
        val acc = IntArray(nTheta * rhoBins)
        var y = max(1, roi.y0)
        val yEnd = min(f.height - 1, roi.y1)
        val xEnd = min(w - 1, roi.x1)
        while (y < yEnd) {
            val row = y * w
            var x = max(1, roi.x0)
            while (x < xEnd) {
                val masked = mask?.get((y - roi.y0) * roi.w + (x - roi.x0)) ?: true
                if (masked) {
                    val gx = (f.luma[row + x + 1].toInt() and 0xFF) - (f.luma[row + x - 1].toInt() and 0xFF)
                    val gy = (f.luma[row + w + x].toInt() and 0xFF) - (f.luma[row - w + x].toInt() and 0xFF)
                    val mag2 = gx * gx + gy * gy
                    if (mag2 >= magThreshold * magThreshold) {
                        val lx = (x - roi.x0).toFloat(); val ly = (y - roi.y0).toFloat()
                        for (t in 0 until nTheta) {
                            val rho = (lx * cosT[t] + ly * sinT[t]).toInt() + diag
                            acc[t * rhoBins + rho]++
                        }
                    }
                }
                x += stride
            }
            y += stride
        }
        // peak detection: bins above a fraction of the max are "lines"
        var maxV = 0
        for (v in acc) if (v > maxV) maxV = v
        if (maxV == 0) return HoughResult(0, 0f)
        val voteThresh = max(8, (maxV * 0.6f).toInt())
        var lines = 0; var strength = 0f
        for (v in acc) if (v >= voteThresh) { lines++; strength += v }
        // confidence: peak vote vs theoretical max votes along the ROI height
        val conf = (maxV.toFloat() / (roi.h.toFloat() / stride)).coerceIn(0f, 1f)
        return HoughResult(lineCount = min(lines, 12), confidence = conf)
    }

    // ---------------------------------------------------------------------------------------------
    // HSV white/yellow gating — lane markings
    // ---------------------------------------------------------------------------------------------

    data class MarkingMask(val mask: BooleanArray, val coverage: Float, val markMeanLuma: Float, val roadMeanLuma: Float)

    /**
     * Builds a boolean mask of marking-like pixels (white OR yellow) in [roi] using an adaptive
     * brightness threshold relative to the road median, and returns coverage + a contrast proxy
     * (marking luma vs surrounding road luma).
     */
    fun markingMask(f: AnalysisFrame, roi: Roi): MarkingMask {
        val stats = lumaStats(f, roi, stride = 2)
        val whiteV = max(150, stats.p50 + 25) // adaptive: brighter than typical road
        val mask = BooleanArray(roi.w * roi.h)
        var markCount = 0
        var markLumaSum = 0.0; var roadLumaSum = 0.0; var roadCount = 0
        var y = roi.y0
        while (y < roi.y1) {
            val row = y * f.width
            var x = roi.x0
            while (x < roi.x1) {
                val i = row + x
                val r = (f.rgb[i] ushr 16) and 0xFF
                val g = (f.rgb[i] ushr 8) and 0xFF
                val b = f.rgb[i] and 0xFF
                val v = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val s = if (v == 0) 0f else (v - mn).toFloat() / v
                val luma = f.luma[i].toInt() and 0xFF
                val isWhite = v >= whiteV && s <= 0.30f
                // yellow: R,G high, B low; hue ~ 40-65deg
                val isYellow = r > 110 && g > 100 && b < g - 25 && b < r - 25 && v >= whiteV - 30
                val mi = (y - roi.y0) * roi.w + (x - roi.x0)
                if (isWhite || isYellow) {
                    mask[mi] = true; markCount++; markLumaSum += luma
                } else {
                    roadLumaSum += luma; roadCount++
                }
                x++
            }
            y++
        }
        val coverage = markCount.toFloat() / (roi.w * roi.h)
        val markMean = if (markCount > 0) (markLumaSum / markCount).toFloat() else 0f
        val roadMean = if (roadCount > 0) (roadLumaSum / roadCount).toFloat() else stats.mean
        return MarkingMask(mask, coverage, markMean, roadMean)
    }

    // ---------------------------------------------------------------------------------------------
    // Dark-channel haze statistic + global contrast (fog/haze)
    // ---------------------------------------------------------------------------------------------

    /**
     * Dark-channel-prior statistic: for each [patch]xpatch block take the minimum across the three
     * channels then the min within the patch; the mean of those minima rises under haze/fog
     * (scattered light lifts the darkest pixels). Returned normalised 0..1.
     */
    fun darkChannelMean(f: AnalysisFrame, patch: Int = 15): Float {
        var sum = 0.0; var n = 0L
        var by = 0
        while (by < f.height) {
            var bx = 0
            while (bx < f.width) {
                var localMin = 255
                var yy = by
                val yEnd = min(by + patch, f.height)
                val xEnd = min(bx + patch, f.width)
                while (yy < yEnd) {
                    val row = yy * f.width
                    var xx = bx
                    while (xx < xEnd) {
                        val i = row + xx
                        val m = min((f.rgb[i] ushr 16) and 0xFF, min((f.rgb[i] ushr 8) and 0xFF, f.rgb[i] and 0xFF))
                        if (m < localMin) localMin = m
                        xx += 2
                    }
                    yy += 2
                }
                sum += localMin; n++
                bx += patch
            }
            by += patch
        }
        if (n == 0L) return 0f
        return (sum / n / 255.0).toFloat().coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------------------------------------
    // Connected-component bright blobs (glare) + motion regions
    // ---------------------------------------------------------------------------------------------

    /** Bright-blob connected components on a downsampled grid (luma >= [threshold]). */
    fun brightBlobs(f: AnalysisFrame, threshold: Int = 245, ds: Int = 2, minSize: Int = 12): List<Blob> {
        val gw = f.width / ds; val gh = f.height / ds
        val label = IntArray(gw * gh) { -1 }
        val blobs = ArrayList<Blob>()
        val stackX = IntArray(gw * gh); val stackY = IntArray(gw * gh)
        var cur = 0
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val gi = gy * gw + gx
                if (label[gi] != -1) continue
                if ((f.luma[(gy * ds) * f.width + gx * ds].toInt() and 0xFF) < threshold) { label[gi] = -2; continue }
                // BFS flood fill
                var sp = 0
                stackX[sp] = gx; stackY[sp] = gy; sp++
                label[gi] = cur
                var size = 0; var sx = 0L; var sy = 0L
                var minX = gx; var minY = gy; var maxX = gx; var maxY = gy
                while (sp > 0) {
                    sp--
                    val cx = stackX[sp]; val cy = stackY[sp]
                    size++; sx += cx; sy += cy
                    if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
                    var dy = -1
                    while (dy <= 1) {
                        var dx = -1
                        while (dx <= 1) {
                            val nx = cx + dx; val ny = cy + dy
                            if (nx in 0 until gw && ny in 0 until gh) {
                                val ni = ny * gw + nx
                                if (label[ni] == -1) {
                                    if ((f.luma[(ny * ds) * f.width + nx * ds].toInt() and 0xFF) >= threshold) {
                                        label[ni] = cur; stackX[sp] = nx; stackY[sp] = ny; sp++
                                    } else label[ni] = -2
                                }
                            }
                            dx++
                        }
                        dy++
                    }
                }
                cur++
                if (size >= minSize) {
                    blobs.add(
                        Blob(
                            size = size * ds * ds,
                            cx = (sx.toFloat() / size) * ds,
                            cy = (sy.toFloat() / size) * ds,
                            x0 = minX * ds, y0 = minY * ds, x1 = maxX * ds, y1 = maxY * ds,
                        )
                    )
                }
            }
        }
        return blobs
    }

    /** Motion region count via abs-difference of two luma frames, blob-counted (chaos fallback). */
    fun motionRegions(prev: AnalysisFrame, cur: AnalysisFrame, diffThresh: Int = 28, ds: Int = 4, minSize: Int = 20): Int {
        if (prev.width != cur.width || prev.height != cur.height) return 0
        val gw = cur.width / ds; val gh = cur.height / ds
        val moving = BooleanArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val i = (gy * ds) * cur.width + gx * ds
            val d = abs((cur.luma[i].toInt() and 0xFF) - (prev.luma[i].toInt() and 0xFF))
            moving[gy * gw + gx] = d >= diffThresh
        }
        // count blobs
        val seen = BooleanArray(gw * gh)
        val sx = IntArray(gw * gh); val sy = IntArray(gw * gh)
        var count = 0
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val gi = gy * gw + gx
            if (seen[gi] || !moving[gi]) continue
            var sp = 0; sx[sp] = gx; sy[sp] = gy; sp++; seen[gi] = true; var size = 0
            while (sp > 0) {
                sp--; val cx = sx[sp]; val cy = sy[sp]; size++
                var dy = -1
                while (dy <= 1) { var dx = -1; while (dx <= 1) {
                    val nx = cx + dx; val ny = cy + dy
                    if (nx in 0 until gw && ny in 0 until gh) {
                        val ni = ny * gw + nx
                        if (!seen[ni] && moving[ni]) { seen[ni] = true; sx[sp] = nx; sy[sp] = ny; sp++ }
                    }
                    dx++ }; dy++ }
            }
            if (size >= minSize) count++
        }
        return count
    }

    // ---------------------------------------------------------------------------------------------
    // Row-wise luma oscillation — streetlight flicker / rolling-shutter banding heuristic
    // ---------------------------------------------------------------------------------------------

    /** Returns a 0..1 banding score: strength of high-frequency oscillation in per-row mean luma. */
    fun rowBanding(f: AnalysisFrame, roi: Roi): Float {
        val rows = roi.h
        if (rows < 8) return 0f
        val rowMean = FloatArray(rows)
        for (ry in 0 until rows) {
            val y = roi.y0 + ry; val row = y * f.width
            var s = 0L
            var x = roi.x0
            while (x < roi.x1) { s += f.luma[row + x].toInt() and 0xFF; x++ }
            rowMean[ry] = s.toFloat() / roi.w
        }
        // high-frequency energy = mean |second difference| relative to local amplitude
        var hf = 0.0; var amp = 0.0
        for (ry in 1 until rows - 1) {
            hf += abs(rowMean[ry - 1] - 2 * rowMean[ry] + rowMean[ry + 1])
            amp += abs(rowMean[ry] - rowMean[ry - 1])
        }
        if (amp < 1e-3) return 0f
        return (hf / (amp + hf)).toFloat().coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------------------------------------
    // Flat-region noise estimate (night high-ISO grain)
    // ---------------------------------------------------------------------------------------------

    /**
     * Noise estimate: in low-gradient (flat) blocks, residual variance after a 3x3 box blur is grain.
     * Returns normalised 0..1.
     */
    fun flatRegionNoise(f: AnalysisFrame, roi: Roi, block: Int = 16): Float {
        var noiseSum = 0.0; var blocks = 0
        var by = roi.y0
        while (by + block < roi.y1) {
            var bx = roi.x0
            while (bx + block < roi.x1) {
                val r = Roi(bx, by, bx + block, by + block)
                val grad = sobel(f, r, bins = 4, stride = 2).edgeEnergy
                if (grad < 0.06f) { // flat block
                    // residual variance vs box-blur
                    var resSq = 0.0; var n = 0
                    var y = by + 1
                    while (y < by + block - 1) {
                        val row = y * f.width
                        var x = bx + 1
                        while (x < bx + block - 1) {
                            var s = 0
                            var dy = -1
                            while (dy <= 1) { var dx = -1; while (dx <= 1) {
                                s += f.luma[row + dy * f.width + x + dx].toInt() and 0xFF; dx++ }; dy++ }
                            val mean = s / 9.0
                            val d = (f.luma[row + x].toInt() and 0xFF) - mean
                            resSq += d * d; n++
                            x++
                        }
                        y++
                    }
                    if (n > 0) { noiseSum += sqrt(resSq / n); blocks++ }
                }
                bx += block
            }
            by += block
        }
        if (blocks == 0) return 0f
        // map RMS grain (~0..20) into 0..1
        return ((noiseSum / blocks) / 18.0).toFloat().coerceIn(0f, 1f)
    }
}
