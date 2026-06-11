package ai.deepmost.corridyx.registry

import ai.deepmost.corridyx.cv.AnalysisFrame
import ai.deepmost.corridyx.cv.Roi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fills a model input ByteBuffer from an [AnalysisFrame] [Roi] with nearest-neighbour resize.
 * Handles uint8-quantised (1 byte/channel) and float32 (4 bytes/channel, normalised to [0,1]) inputs.
 * Pure, allocation-light, runs on the analysis executor.
 */
object TensorPrep {

    fun allocInput(entry: ModelEntry): ByteBuffer {
        val bytesPerChannel = if (entry.quantized) 1 else 4
        return ByteBuffer
            .allocateDirect(entry.inputWidth * entry.inputHeight * entry.inputChannels * bytesPerChannel)
            .order(ByteOrder.nativeOrder())
    }

    /** Resize-fill [buf] (rewound) from [roi] of [f] into entry.inputWidth x inputHeight RGB. */
    fun fill(buf: ByteBuffer, f: AnalysisFrame, roi: Roi, entry: ModelEntry) {
        buf.rewind()
        val ow = entry.inputWidth; val oh = entry.inputHeight
        val quant = entry.quantized
        for (oy in 0 until oh) {
            val sy = roi.y0 + (oy * roi.h / oh).coerceIn(0, roi.h - 1)
            val rowBase = sy * f.width
            for (ox in 0 until ow) {
                val sx = roi.x0 + (ox * roi.w / ow).coerceIn(0, roi.w - 1)
                val px = f.rgb[rowBase + sx]
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                if (quant) {
                    buf.put(r.toByte()); buf.put(g.toByte()); buf.put(b.toByte())
                } else {
                    buf.putFloat(r / 255f); buf.putFloat(g / 255f); buf.putFloat(b / 255f)
                }
            }
        }
        buf.rewind()
    }
}
