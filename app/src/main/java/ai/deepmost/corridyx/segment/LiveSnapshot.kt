package ai.deepmost.corridyx.segment

import ai.deepmost.corridyx.capture.TimeBucket
import ai.deepmost.corridyx.conditions_api.PerceptionConditions

/** ~10 fps UI snapshot published by the accumulator actor for the DRIVE screen. */
data class LiveSnapshot(
    val segmentId: String?,        // active SegmentKey.encode() or null on GPS gap
    val timeBucket: TimeBucket,
    val liveOdd: Float,            // composite 0..100 of the active segment so far
    val dimMeans: Map<String, Float>,
    val instantSamples: Map<String, Float>, // this tick's per-dimension scores (for mini-bars)
    val conditions: PerceptionConditions,
    val dutyCycle: DutyCycleState,
    val speedMps: Float,
    val gpsValid: Boolean,
    val frameCount: Int,
) {
    companion object {
        val INITIAL = LiveSnapshot(
            segmentId = null,
            timeBucket = TimeBucket.DAY,
            liveOdd = 0f,
            dimMeans = emptyMap(),
            instantSamples = emptyMap(),
            conditions = PerceptionConditions.UNKNOWN,
            dutyCycle = DutyCycleState.NORMAL,
            speedMps = 0f,
            gpsValid = false,
            frameCount = 0,
        )
    }
}
