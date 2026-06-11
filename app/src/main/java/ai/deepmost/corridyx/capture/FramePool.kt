package ai.deepmost.corridyx.capture

import ai.deepmost.corridyx.cv.AnalysisFrame

/**
 * Tiny ring of reusable [AnalysisFrame] buffers (cur + prev + one spare). Latest-frame-wins: the
 * analyzer writes into the next buffer, keeps the previous for motion/scene-change heads, and never
 * allocates on the hot path once warmed. Single-writer (analysis executor).
 */
class FramePool(private val width: Int, private val height: Int) {
    private val buffers = Array(3) { AnalysisFrame(width, height) }
    private var idx = 0
    var current: AnalysisFrame? = null
        private set
    var previous: AnalysisFrame? = null
        private set

    /** Returns the next write buffer; caller fills it then calls [commit]. */
    fun next(): AnalysisFrame {
        idx = (idx + 1) % buffers.size
        return buffers[idx]
    }

    fun commit(frame: AnalysisFrame) {
        previous = current
        current = frame
    }

    fun matches(w: Int, h: Int) = w == width && h == height
}
