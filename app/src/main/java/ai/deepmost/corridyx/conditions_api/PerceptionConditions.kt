package ai.deepmost.corridyx.conditions_api

import ai.deepmost.corridyx.dimensions.Dim
import ai.deepmost.corridyx.dimensions.MetricSample

/**
 * Live perception-weather state exposed by the GLARYX subsystem to this app AND to sibling
 * ai.deepmost.* perception apps (FERYX, LANYX) via the bound service.
 *
 * Each of [lens]/[glare]/[atmo]/[nightq] is a SEVERITY in 0..1 (0 = clean seeing, 1 = fully
 * degraded). [overallTrust] in 0..1 (1 = fully trustworthy) is the compounded product of the
 * non-degraded factors — a single number a consumer app can threshold to decide whether its own
 * detections are reliable right now.
 */
data class PerceptionConditions(
    val lens: Float,
    val glare: Float,
    val atmo: Float,
    val nightq: Float,
    val overallTrust: Float,
    val timestampMs: Long,
) {
    companion object {
        val UNKNOWN = PerceptionConditions(0f, 0f, 0f, 0f, 1f, 0L)

        /** Build from this tick's GLARYX samples; missing heads default to no-degradation. */
        fun from(samples: List<MetricSample>, wallMs: Long): PerceptionConditions {
            val byDim = samples.associateBy { it.dimension }
            fun severity(dim: String, rawKey: String?): Float {
                val s = byDim[dim] ?: return 0f
                val raw = rawKey?.let { s.raw[it] }
                return (raw ?: (1f - s.score)).coerceIn(0f, 1f)
            }
            val lens = severity(Dim.LENS, "dirtFraction")
            val glare = severity(Dim.GLARE, "severity")
            val atmo = byDim[Dim.ATMO]?.let {
                maxOf(it.raw["rainSeverity"] ?: 0f, it.raw["hazeSeverity"] ?: (1f - it.score))
            } ?: 0f
            val nightq = severity(Dim.NIGHTQ, null)  // 1 - nightSeeing
            val trust = ((1f - lens) * (1f - glare) * (1f - atmo) * (1f - nightq)).coerceIn(0f, 1f)
            return PerceptionConditions(lens, glare, atmo.coerceIn(0f, 1f), nightq, trust, wallMs)
        }
    }
}
