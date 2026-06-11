package ai.deepmost.corridyx.cv

/**
 * One downscaled analysis frame. Owns both an 8-bit luma plane and a packed RGB plane at the
 * same [width]x[height], so heads that need only intensity (sharpness, glare, roughness-irrelevant)
 * read [luma] while colour-gating heads (lane white/yellow, glare saturation) read [rgb].
 *
 * Buffers are pooled and reused (latest-frame-wins): a head must finish reading before the next
 * frame is published. All values are plain arrays — no Bitmap on the hot path.
 */
class AnalysisFrame(
    val width: Int,
    val height: Int,
) {
    /** Row-major luma, 0..255 stored unsigned in a ByteArray. Read with [l]. */
    val luma: ByteArray = ByteArray(width * height)

    /** Row-major packed 0xFFRRGGBB. */
    val rgb: IntArray = IntArray(width * height)

    var timestampNs: Long = 0L

    /** Unsigned luma at (x,y). No bounds check on the hot path; callers stay in range. */
    inline fun l(x: Int, y: Int): Int = luma[y * width + x].toInt() and 0xFF
    inline fun li(i: Int): Int = luma[i].toInt() and 0xFF

    inline fun r(i: Int): Int = (rgb[i] ushr 16) and 0xFF
    inline fun g(i: Int): Int = (rgb[i] ushr 8) and 0xFF
    inline fun b(i: Int): Int = rgb[i] and 0xFF
}
