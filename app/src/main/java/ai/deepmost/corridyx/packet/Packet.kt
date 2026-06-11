package ai.deepmost.corridyx.packet

import kotlinx.serialization.Serializable

/**
 * SegmentScorePacket — the product unit. One finalized segment-visit, serialized to manifest.json
 * and (optionally) zipped with blurred evidence thumbnails for fleet upload. Every score carries
 * its breakdown (mean/var/min/max/n/engine + raw submetric means), the gates that fired, the
 * capability context, and the duty-cycle state, so aggregation is fully explainable.
 */
@Serializable
data class SegmentScorePacket(
    val packetId: String,
    val nodeId: String,
    val appVersion: String,
    val schemaVersion: Int,
    val sessionId: String,
    val segment: SegmentRef,
    val window: Window,
    val timeBucket: String,        // DAY / DUSK_DAWN / NIGHT
    val weatherContext: String,    // clear / haze / fog / rain / rain_fog (from atmo head)
    val oddScore: Float,           // composite 0..100
    val scores: Map<String, DimScore>,
    val gates: List<GateRecord>,
    val capability: Capability,
    val evidence: List<EvidenceRef>,
)

@Serializable
data class SegmentRef(
    val geohash7: String,
    val headingBucket: Int,
    val centroidLat: Double,
    val centroidLon: Double,
)

@Serializable
data class Window(
    val enterTs: Long,
    val exitTs: Long,
    val sampleCounts: Map<String, Int>,
    val dutyCycleState: String,     // NORMAL / REDUCED / THROTTLED
    val speedMeanMps: Float,
    val speedMinMps: Float,
    val speedMaxMps: Float,
    val distanceM: Float,
    val frameCount: Int,
)

@Serializable
data class DimScore(
    val mean: Float,
    val variance: Float,
    val min: Float,
    val max: Float,
    val n: Long,
    val engine: String,
    val rawMeans: Map<String, Float>,
)

@Serializable
data class GateRecord(
    val dimension: String,
    val invalidatedCount: Int,
    val reason: String,             // e.g. "lens_dirt>0.35", "glare>0.6", "mount_vibration>0.7"
)

@Serializable
data class Capability(
    val rawGnssSupported: Boolean,
    val gnssCapability: String,     // RAW_MEASUREMENTS / STATUS_ONLY / NONE
    val lightSensorPresent: Boolean,
    val modelVersions: Map<String, String>,  // head -> "<modelId>@<version>" (only registered models)
)

@Serializable
data class EvidenceRef(
    val file: String,               // relative file name inside the packet dir / zip
    val dimension: String,
    val reason: String,
    val blurRegions: List<BlurRegion>,
)

@Serializable
data class BlurRegion(
    val x: Int, val y: Int, val w: Int, val h: Int,
    val kind: String,               // face / plate
)
