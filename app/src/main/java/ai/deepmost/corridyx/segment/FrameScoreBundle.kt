package ai.deepmost.corridyx.segment

import ai.deepmost.corridyx.capture.GnssCapability
import ai.deepmost.corridyx.capture.TimeBucket
import ai.deepmost.corridyx.dimensions.MetricSample
import ai.deepmost.corridyx.packet.EvidenceCandidate

/** Duty-cycle state of the analyzer, recorded in packets so aggregation can weight by it. */
enum class DutyCycleState { NORMAL, REDUCED, THROTTLED }

/**
 * Everything one analysis tick contributes to accumulation. Emitted by the pipeline to the
 * single-writer [AccumulatorActor]. Carries the already-resolved segment, the per-head samples,
 * the live context for gating + speed stats, and an optional evidence candidate for this frame.
 */
data class FrameScoreBundle(
    val timestampNs: Long,
    val wallMs: Long,
    val segmentKey: SegmentKey?,        // null when GPS gap -> recorded as a gap, no accumulation
    val centroidLat: Double,
    val centroidLon: Double,
    val timeBucket: TimeBucket,
    val samples: List<MetricSample>,
    val speedMps: Float,
    val distanceDeltaM: Float,
    val dutyCycle: DutyCycleState,
    val rawGnssSupported: Boolean,
    val gnssCapability: GnssCapability,
    val lightSensorPresent: Boolean,
    val evidence: EvidenceCandidate?,
)
