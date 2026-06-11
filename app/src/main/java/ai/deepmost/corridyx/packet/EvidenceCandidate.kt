package ai.deepmost.corridyx.packet

/**
 * A captured-but-not-yet-persisted evidence frame. Holds a downscaled RGB copy ONLY (no continuous
 * video, never the full frame stream). Privacy blur is applied at packet-assembly time before this
 * ever touches disk. [vehicleBoxes] are normalised detector boxes (if a detector ran) used as the
 * heuristic plate-strip blur seed.
 */
class EvidenceCandidate(
    val width: Int,
    val height: Int,
    val rgb: IntArray,                 // packed 0xFFRRGGBB, length width*height
    val dimension: String,
    val reason: String,
    val score: Float,                  // the extreme value that earned this thumbnail
    val vehicleBoxes: List<FloatArray>, // each [x0,y0,x1,y1] normalised 0..1, or empty
)
