package ai.deepmost.corridyx.dimensions

import ai.deepmost.corridyx.capture.GnssSnapshot
import ai.deepmost.corridyx.capture.ImuWindow
import ai.deepmost.corridyx.capture.LocationSnapshot
import ai.deepmost.corridyx.capture.SunGeometry
import ai.deepmost.corridyx.capture.TimeBucket
import ai.deepmost.corridyx.cv.AnalysisFrame

/** Stable dimension identifiers — also the packet keys. */
object Dim {
    const val LANE_VIS = "lane_vis"
    const val GNSS_Q = "gnss_q"
    const val ILLUM = "illum"
    const val CHAOS = "chaos"
    const val ROUGH = "rough"
    const val LENS = "lens"
    const val GLARE = "glare"
    const val ATMO = "atmo"
    const val NIGHTQ = "nightq"

    val ALL = listOf(LANE_VIS, GNSS_Q, ILLUM, CHAOS, ROUGH, LENS, GLARE, ATMO, NIGHTQ)
    /** The GLARYX perception-weather heads. */
    val GLARYX = listOf(LENS, GLARE, ATMO, NIGHTQ)
}

/**
 * Everything one analysis tick can see. Built once per published frame on the analysis executor
 * and fanned out to every enabled [OddDimension]. Nullable members reflect honest sensor
 * availability — heads degrade gracefully and never fabricate.
 */
data class FrameContext(
    val frame: AnalysisFrame,
    val prevFrame: AnalysisFrame?,
    val timestampNs: Long,
    val wallClockMs: Long,
    val location: LocationSnapshot?,
    val gnss: GnssSnapshot?,
    val imu: ImuWindow?,
    val lightLux: Float?,
    val sun: SunGeometry?,
    val timeBucket: TimeBucket,
    /** Vehicle heading from movement bearing, degrees 0..360, or null when stationary/no fix. */
    val headingDeg: Float?,
)

/**
 * One per-frame metric sample produced by a head. [score] is the normalised 0..1 ODD subscore
 * (1 = best for autonomy). [raw] carries the exact submetric values that produced it so every
 * score is explainable and reproducible. [engine] records what computed it
 * (e.g. "classical" or "lane_seg@1.2.0"). A head returns null when it cannot validly sample this
 * tick (recorded upstream as a gap, never as a zero).
 */
data class MetricSample(
    val dimension: String,
    val score: Float,
    val raw: Map<String, Float>,
    val engine: String,
    val timestampNs: Long,
    /**
     * Local validity from the head's own perspective (e.g. roughness below min speed -> false).
     * Cross-dimension sensor-fault gating (lens dirt invalidating lane_vis) happens later in Fusion.
     */
    val locallyValid: Boolean = true,
)

/** Engine label constants. */
object Engine {
    const val CLASSICAL = "classical"
    const val FAILED = "failed"
}

/**
 * A pluggable ODD scoring dimension. Implementations are stateless-per-call where possible;
 * temporal heads (lens dirt, churn) hold bounded internal state guarded by single-writer access
 * from the analysis executor. process() MUST NOT throw — a head that fails returns a FAILED-engine
 * sample so one broken head never kills the session.
 */
interface OddDimension {
    val id: String

    /** @return a sample, or null if this head does not apply to this tick. */
    fun process(ctx: FrameContext): MetricSample?

    /** Release any native/model resources. */
    fun close() {}
}
