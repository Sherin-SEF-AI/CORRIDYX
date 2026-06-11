package ai.deepmost.corridyx.capture

import ai.deepmost.corridyx.cv.AnalysisFrame
import androidx.camera.core.ImageProxy

/**
 * Converts a CameraX YUV_420_888 [ImageProxy] into a downscaled, rotation-corrected [AnalysisFrame]
 * (luma + packed RGB). Nearest-neighbour sampling with a stride keeps it cheap. The output is our
 * own buffer, so the ImageProxy can be closed immediately after conversion (no continuous video
 * retained — only this transient analysis frame).
 */
object YuvConverter {

    /** Choose output dimensions for a target longest-edge, honouring rotation (swap on 90/270). */
    fun outputDims(image: ImageProxy, targetLongEdge: Int): Pair<Int, Int> {
        val rot = image.imageInfo.rotationDegrees
        val srcW = image.width; val srcH = image.height
        val (w, h) = if (rot == 90 || rot == 270) srcH to srcW else srcW to srcH
        val scale = targetLongEdge.toFloat() / maxOf(w, h)
        val ow = (w * scale).toInt().coerceAtLeast(16)
        val oh = (h * scale).toInt().coerceAtLeast(16)
        return ow to oh
    }

    fun fill(image: ImageProxy, out: AnalysisFrame) {
        val rot = image.imageInfo.rotationDegrees
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer; val uBuf = uPlane.buffer; val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride; val yPixStride = yPlane.pixelStride
        val uvRowStride = uPlane.rowStride; val uvPixStride = uPlane.pixelStride

        val srcW = image.width; val srcH = image.height
        val ow = out.width; val oh = out.height

        for (oy in 0 until oh) {
            for (ox in 0 until ow) {
                // map output (post-rotation) coords back to source coords
                val sxsy = mapSource(ox, oy, ow, oh, srcW, srcH, rot)
                val sx = sxsy[0]; val sy = sxsy[1]
                val y = yBuf.get(sy * yRowStride + sx * yPixStride).toInt() and 0xFF
                val uvx = sx / 2; val uvy = sy / 2
                val u = uBuf.get(uvy * uvRowStride + uvx * uvPixStride).toInt() and 0xFF
                val v = vBuf.get(uvy * uvRowStride + uvx * uvPixStride).toInt() and 0xFF
                val oi = oy * ow + ox
                out.luma[oi] = y.toByte()
                out.rgb[oi] = yuvToRgb(y, u, v)
            }
        }
        out.timestampNs = image.imageInfo.timestamp
    }

    private fun mapSource(ox: Int, oy: Int, ow: Int, oh: Int, srcW: Int, srcH: Int, rot: Int): IntArray {
        // normalised position then rotate into source frame
        return when (rot) {
            90 -> {
                val sx = (oy.toFloat() / oh * srcW).toInt().coerceIn(0, srcW - 1)
                val sy = ((ow - 1 - ox).toFloat() / ow * srcH).toInt().coerceIn(0, srcH - 1)
                intArrayOf(sx, sy)
            }
            180 -> {
                val sx = ((ow - 1 - ox).toFloat() / ow * srcW).toInt().coerceIn(0, srcW - 1)
                val sy = ((oh - 1 - oy).toFloat() / oh * srcH).toInt().coerceIn(0, srcH - 1)
                intArrayOf(sx, sy)
            }
            270 -> {
                val sx = ((oh - 1 - oy).toFloat() / oh * srcW).toInt().coerceIn(0, srcW - 1)
                val sy = (ox.toFloat() / ow * srcH).toInt().coerceIn(0, srcH - 1)
                intArrayOf(sx, sy)
            }
            else -> {
                val sx = (ox.toFloat() / ow * srcW).toInt().coerceIn(0, srcW - 1)
                val sy = (oy.toFloat() / oh * srcH).toInt().coerceIn(0, srcH - 1)
                intArrayOf(sx, sy)
            }
        }
    }

    private fun yuvToRgb(y: Int, u: Int, v: Int): Int {
        val c = y - 16; val d = u - 128; val e = v - 128
        var r = (298 * c + 409 * e + 128) shr 8
        var g = (298 * c - 100 * d - 208 * e + 128) shr 8
        var b = (298 * c + 516 * d + 128) shr 8
        if (r < 0) r = 0 else if (r > 255) r = 255
        if (g < 0) g = 0 else if (g > 255) g = 255
        if (b < 0) b = 0 else if (b > 255) b = 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
