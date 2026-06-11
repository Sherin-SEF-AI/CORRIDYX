package ai.deepmost.corridyx.fuse

import ai.deepmost.corridyx.config.Settings
import ai.deepmost.corridyx.dimensions.Dim
import ai.deepmost.corridyx.dimensions.MetricSample

/**
 * Sensor-fault gating + composite ODD fusion.
 *
 * GATING (essential): a sensor fault must NOT masquerade as a corridor property. When the camera's
 * own seeing is compromised, the affected corridor-property samples are INVALIDATED (dropped +
 * counted as gated) rather than dragging the corridor score down:
 *   - lane_vis sample dropped when lens dirtFraction > gateLensDirtInvalidatesLaneVis
 *       OR glare severity > gateGlareInvalidatesLaneVis
 *   - rough sample dropped when its own mountContam > gateMountVibInvalidatesRough
 *       (mount resonance contaminating the IMU is a sensor fault, not road roughness)
 *
 * COMPOSITE: per segment-visit per time-bucket, a weighted blend of the surviving dimension means
 * into 0..100. The four GLARYX heads collapse into one "perception" trust term (mean of available
 * heads). Weights are normalised over the dimensions actually present.
 */
object Fusion {

    /** @return dimension -> reason, for samples that must be dropped THIS frame. */
    fun evaluateGates(samples: List<MetricSample>, s: Settings): Map<String, String> {
        val byDim = samples.associateBy { it.dimension }
        val out = HashMap<String, String>()

        val dirt = byDim[Dim.LENS]?.raw?.get("dirtFraction") ?: 0f
        val glare = byDim[Dim.GLARE]?.raw?.get("severity") ?: 0f
        if (byDim.containsKey(Dim.LANE_VIS)) {
            when {
                dirt > s.gateLensDirtInvalidatesLaneVis ->
                    out[Dim.LANE_VIS] = "lens_dirt>${s.gateLensDirtInvalidatesLaneVis}"
                glare > s.gateGlareInvalidatesLaneVis ->
                    out[Dim.LANE_VIS] = "glare>${s.gateGlareInvalidatesLaneVis}"
            }
        }
        val mountContam = byDim[Dim.ROUGH]?.raw?.get("mountContam") ?: 0f
        if (byDim.containsKey(Dim.ROUGH) && mountContam > s.gateMountVibInvalidatesRough) {
            out[Dim.ROUGH] = "mount_vibration>${s.gateMountVibInvalidatesRough}"
        }
        return out
    }

    /**
     * Composite ODD 0..100 from surviving dimension means. [dimMeans] holds whatever dimensions had
     * at least one accepted sample; absent dimensions are simply excluded and weights renormalise.
     */
    fun composite(dimMeans: Map<String, Float>, s: Settings): Float {
        // collapse GLARYX heads into one perception trust term
        val perceptionHeads = Dim.GLARYX.mapNotNull { dimMeans[it] }
        val perception = if (perceptionHeads.isNotEmpty()) perceptionHeads.average().toFloat() else null

        val terms = ArrayList<Pair<Float, Float>>() // (weight, value)
        dimMeans[Dim.LANE_VIS]?.let { terms.add(s.wLaneVis to it) }
        dimMeans[Dim.GNSS_Q]?.let { terms.add(s.wGnssQ to it) }
        dimMeans[Dim.ILLUM]?.let { terms.add(s.wIllum to it) }
        dimMeans[Dim.CHAOS]?.let { terms.add(s.wChaos to it) }
        dimMeans[Dim.ROUGH]?.let { terms.add(s.wRough to it) }
        perception?.let { terms.add(s.wPerception to it) }

        val wSum = terms.sumOf { it.first.toDouble() }.toFloat()
        if (wSum <= 0f) return 0f
        val blend = terms.sumOf { (it.first * it.second).toDouble() }.toFloat() / wSum
        return (blend * 100f).coerceIn(0f, 100f)
    }

    /** Weather context label for the packet, from atmo raw submetric means. */
    fun weatherContext(rainSeverity: Float, hazeSeverity: Float): String = when {
        rainSeverity >= 0.5f && hazeSeverity >= 0.4f -> "rain_fog"
        rainSeverity >= 0.5f -> "rain"
        hazeSeverity >= 0.6f -> "fog"
        hazeSeverity >= 0.35f -> "haze"
        else -> "clear"
    }
}
