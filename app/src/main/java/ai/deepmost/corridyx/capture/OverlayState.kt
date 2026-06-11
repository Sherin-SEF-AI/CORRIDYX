package ai.deepmost.corridyx.capture

import ai.deepmost.corridyx.cv.Blob

/**
 * Live overlay primitives for the DRIVE preview (the dominant degradation is drawn over the camera
 * strip). Coordinates are in analysis-frame space; the UI scales them to the preview.
 */
data class OverlayState(
    val frameW: Int,
    val frameH: Int,
    val lensGridCols: Int,
    val lensGridRows: Int,
    val lensDirtMask: BooleanArray,   // row-major cols*rows persistent-dirt blocks
    val glareBlobs: List<Blob>,
    val dominant: String,             // dimension id of the worst current degradation, or ""
) {
    companion object {
        val EMPTY = OverlayState(0, 0, 0, 0, BooleanArray(0), emptyList(), "")
    }
}
