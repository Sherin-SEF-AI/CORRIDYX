package ai.deepmost.corridyx.privacy

import ai.deepmost.corridyx.packet.BlurRegion
import ai.deepmost.corridyx.packet.EvidenceCandidate
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Offline privacy blur for evidence thumbnails. Runs BEFORE any thumbnail is persisted or uploaded
 * and CANNOT be disabled for uploads (blur is always on). Reuse-style:
 *   - ML Kit on-device face detection (FAST mode, bundled) -> pixelate each face box
 *   - heuristic plate-strip blur on detector-provided vehicle boxes (lower-centre band where the
 *     number plate sits) -> pixelate
 * Pixelation (mosaic) is irreversible. Returns the blurred bitmap and the regions it touched.
 */
class RegionBlurrer {

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
        )
    }

    data class Result(val bitmap: Bitmap, val regions: List<BlurRegion>)

    /** Build a mutable bitmap from the candidate, blur faces + plate strips, return both. */
    fun blur(candidate: EvidenceCandidate): Result {
        val bmp = Bitmap.createBitmap(candidate.rgb, candidate.width, candidate.height, Bitmap.Config.ARGB_8888)
            .copy(Bitmap.Config.ARGB_8888, true)
        val regions = ArrayList<BlurRegion>()

        // faces
        runCatching {
            val image = InputImage.fromBitmap(bmp, 0)
            val faces = Tasks.await(faceDetector.process(image), 1500, TimeUnit.MILLISECONDS)
            for (f in faces) {
                val r = f.boundingBox
                val pad = (max(r.width(), r.height()) * 0.15f).toInt()
                pixelate(bmp, r.left - pad, r.top - pad, r.width() + 2 * pad, r.height() + 2 * pad)
                regions.add(BlurRegion(r.left - pad, r.top - pad, r.width() + 2 * pad, r.height() + 2 * pad, "face"))
            }
        }.onFailure { Timber.w(it, "face detect/blur failed (continuing with plate strips)") }

        // plate strips from vehicle boxes
        for (box in candidate.vehicleBoxes) {
            val bx0 = (box[0] * candidate.width)
            val by0 = (box[1] * candidate.height)
            val bx1 = (box[2] * candidate.width)
            val by1 = (box[3] * candidate.height)
            val bw = bx1 - bx0; val bh = by1 - by0
            // plate sits in the lower ~35%, central ~60%
            val px = (bx0 + bw * 0.20f).toInt()
            val py = (by0 + bh * 0.62f).toInt()
            val pw = (bw * 0.60f).toInt()
            val ph = (bh * 0.36f).toInt()
            pixelate(bmp, px, py, pw, ph)
            regions.add(BlurRegion(px, py, pw, ph, "plate"))
        }
        return Result(bmp, regions)
    }

    /** Irreversible mosaic over a region, clamped to bitmap bounds. */
    private fun pixelate(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int, block: Int = 12) {
        val x0 = max(0, x); val y0 = max(0, y)
        val x1 = min(bmp.width, x + w); val y1 = min(bmp.height, y + h)
        if (x1 <= x0 || y1 <= y0) return
        var by = y0
        while (by < y1) {
            var bx = x0
            while (bx < x1) {
                val bw = min(block, x1 - bx); val bh = min(block, y1 - by)
                var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
                for (yy in by until by + bh) for (xx in bx until bx + bw) {
                    val c = bmp.getPixel(xx, yy)
                    rs += (c ushr 16) and 0xFF; gs += (c ushr 8) and 0xFF; bs += c and 0xFF; n++
                }
                if (n > 0) {
                    val avg = (0xFF shl 24) or (((rs / n).toInt()) shl 16) or (((gs / n).toInt()) shl 8) or ((bs / n).toInt())
                    for (yy in by until by + bh) for (xx in bx until bx + bw) bmp.setPixel(xx, yy, avg)
                }
                bx += block
            }
            by += block
        }
    }

    fun close() { runCatching { faceDetector.close() } }
}
