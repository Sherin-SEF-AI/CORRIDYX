package ai.deepmost.corridyx.config

/**
 * Immutable snapshot of all user-configurable settings (SETTINGS screen, DataStore-backed).
 *
 * Every threshold / weight referenced by the scoring pipeline lives here so the whole
 * system is reproducible from one struct. Defaults are the day-one shipping values and
 * mirror assets/default_config.json.
 */
data class Settings(
    // --- Capture ---
    val analysisFps: Int = 5,                 // CameraX analysis cadence (frames/sec) before duty cycling
    val minSpeedMpsRoughness: Float = 2.5f,   // gate: roughness needs real motion (~9 km/h)
    val minSpeedMpsAccumulate: Float = 0.5f,  // below this we still accumulate non-motion dims

    // --- Segmentation ---
    val segmentHysteresisMeters: Float = 25f, // must travel this far past the boundary to finalise
    val segmentHysteresisFrames: Int = 8,     // and persist in the new cell for this many frames

    // --- Per-dimension enable flags ---
    val enableLaneVis: Boolean = true,
    val enableGnssQ: Boolean = true,
    val enableIllum: Boolean = true,
    val enableChaos: Boolean = true,
    val enableRough: Boolean = true,
    val enableLens: Boolean = true,
    val enableGlare: Boolean = true,
    val enableAtmo: Boolean = true,
    val enableNightQ: Boolean = true,

    // --- Composite ODD fusion weights (normalised at fuse time) ---
    val wLaneVis: Float = 0.22f,
    val wGnssQ: Float = 0.18f,
    val wIllum: Float = 0.12f,
    val wChaos: Float = 0.14f,
    val wRough: Float = 0.10f,
    val wPerception: Float = 0.24f,           // combined GLARYX trust contribution

    // --- Sensor-fault gate thresholds (see Fusion docs) ---
    val gateLensDirtInvalidatesLaneVis: Float = 0.35f, // dirt fraction above which lane_vis samples are dropped
    val gateGlareInvalidatesLaneVis: Float = 0.6f,     // glare severity above which lane_vis samples are dropped
    val gateMountVibInvalidatesRough: Float = 0.7f,    // mount-resonance contamination above which rough is dropped

    // --- Driver alert thresholds ---
    val alertLensDirt: Float = 0.45f,
    val alertGlare: Float = 0.65f,
    val alertHeavyRain: Float = 0.6f,
    val alertCooldownMs: Long = 20_000L,
    val ttsAlerts: Boolean = false,

    // --- Evidence thumbnails ---
    val evidenceEnabled: Boolean = true,
    val evidenceMaxPerSegment: Int = 2,
    val evidenceThumbMaxDim: Int = 320,       // longest-edge px of the persisted blurred thumbnail

    // --- Upload ---
    val uploadEndpoint: String = "",          // empty => packets stay queued locally
    val uploadToken: String = "",
    val uploadWifiOnly: Boolean = true,
    val nodeId: String = "",                  // vehicle node id; blank => derived from install id
    val vehicleLabel: String = "",

    // --- Storage / thermal ---
    val storageCapMb: Int = 512,              // global cap; oldest-uploaded packets evicted first
    val thermalAggressiveness: Float = 1.0f,  // 0..2 scales how hard duty cycling cuts FPS under heat
) {
    companion object {
        val DEFAULT = Settings()
    }
}
