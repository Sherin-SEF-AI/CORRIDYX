package ai.deepmost.corridyx.segment

import ai.deepmost.corridyx.capture.GnssCapability
import ai.deepmost.corridyx.capture.TimeBucket
import ai.deepmost.corridyx.config.Settings
import ai.deepmost.corridyx.dimensions.Dim
import ai.deepmost.corridyx.dimensions.Engine
import ai.deepmost.corridyx.fuse.Fusion
import ai.deepmost.corridyx.packet.DimScore
import ai.deepmost.corridyx.packet.EvidenceCandidate
import ai.deepmost.corridyx.packet.GateRecord
import ai.deepmost.corridyx.packet.SegmentRef
import ai.deepmost.corridyx.packet.Window
import kotlinx.serialization.Serializable

/** Per-dimension running stats for one segment-visit. */
private class DimAcc(
    var welford: Welford = Welford(),
    var engine: String = Engine.CLASSICAL,
    val raw: HashMap<String, RunningMean> = HashMap(),
) {
    fun add(score: Float, rawMap: Map<String, Float>, eng: String) {
        welford.add(score)
        engine = eng
        for ((k, v) in rawMap) raw.getOrPut(k) { RunningMean() }.add(v)
    }
    fun rawMeans(): Map<String, Float> = raw.mapValues { it.value.mean() }
}

/** Output of finalising a segment-visit: packet-ready fields + evidence still to be blurred/saved. */
class SegmentResult(
    val key: SegmentKey,
    val segmentRef: SegmentRef,
    val window: Window,
    val timeBucket: TimeBucket,
    val weatherContext: String,
    val oddScore: Float,
    val scores: Map<String, DimScore>,
    val gates: List<GateRecord>,
    val capability: CapabilitySnapshot,
    val evidence: List<EvidenceCandidate>,
)

data class CapabilitySnapshot(
    val rawGnssSupported: Boolean,
    val gnssCapability: GnssCapability,
    val lightSensorPresent: Boolean,
)

/**
 * Single-writer (actor-owned) accumulator for one active segment-visit. Applies sensor-fault gating
 * at accumulation time so invalidated samples are counted, not scored. Bounded: evidence is capped
 * and dimension stats are constant-size Welford/running means. Snapshots to disk for process-death
 * safety (evidence pixels excluded — bounded disk, rebuilt next visit).
 */
class SegmentAccumulator(
    val key: SegmentKey,
    val centroidLat: Double,
    val centroidLon: Double,
    val enterWallMs: Long,
    val timeBucket: TimeBucket,
) {
    private val dims = HashMap<String, DimAcc>()
    private val gateCounts = HashMap<String, Pair<Int, String>>()
    private val failedHeads = HashSet<String>()
    private var speed = Welford()
    private var distanceM = 0f
    private var frameCount = 0
    private var lastWallMs = enterWallMs
    private var worstDuty = DutyCycleState.NORMAL
    private var rainMean = RunningMean()
    private var hazeMean = RunningMean()
    private val evidence = ArrayList<EvidenceCandidate>()
    private var lastSettings = Settings.DEFAULT

    private var rawGnssSupported = false
    private var gnssCapability = GnssCapability.NONE
    private var lightSensorPresent = false

    fun frames(): Int = frameCount

    fun add(bundle: FrameScoreBundle, settings: Settings) {
        lastSettings = settings
        lastWallMs = bundle.wallMs
        frameCount++
        distanceM += bundle.distanceDeltaM
        if (bundle.speedMps >= 0f) speed.add(bundle.speedMps)
        if (bundle.dutyCycle.ordinal > worstDuty.ordinal) worstDuty = bundle.dutyCycle
        rawGnssSupported = rawGnssSupported || bundle.rawGnssSupported
        gnssCapability = bestCapability(gnssCapability, bundle.gnssCapability)
        lightSensorPresent = lightSensorPresent || bundle.lightSensorPresent

        val gates = Fusion.evaluateGates(bundle.samples, settings)

        for (sample in bundle.samples) {
            if (sample.engine == Engine.FAILED) { failedHeads.add(sample.dimension); continue }
            if (!sample.locallyValid) continue // recorded as a gap (no accumulation)
            val gateReason = gates[sample.dimension]
            if (gateReason != null) {
                val prev = gateCounts[sample.dimension]
                gateCounts[sample.dimension] = Pair((prev?.first ?: 0) + 1, gateReason)
                continue
            }
            dims.getOrPut(sample.dimension) { DimAcc() }.add(sample.score, sample.raw, sample.engine)
            if (sample.dimension == Dim.ATMO) {
                sample.raw["rainSeverity"]?.let { rainMean.add(it) }
                sample.raw["hazeSeverity"]?.let { hazeMean.add(it) }
            }
        }

        val cand = bundle.evidence
        if (cand != null && settings.evidenceEnabled && settings.evidenceMaxPerSegment > 0) {
            if (evidence.size < settings.evidenceMaxPerSegment) {
                evidence.add(cand)
            } else {
                val weakestIdx = evidence.indices.minByOrNull { extremeness(evidence[it]) }
                if (weakestIdx != null && extremeness(cand) > extremeness(evidence[weakestIdx])) {
                    evidence[weakestIdx] = cand
                }
            }
        }
    }

    private fun extremeness(c: EvidenceCandidate): Float = 1f - c.score

    fun finalize(): SegmentResult {
        val scores = HashMap<String, DimScore>()
        for ((dim, acc) in dims) {
            scores[dim] = DimScore(
                mean = acc.welford.mean(),
                variance = acc.welford.variance(),
                min = acc.welford.minOrZero(),
                max = acc.welford.maxOrZero(),
                n = acc.welford.n,
                engine = acc.engine,
                rawMeans = acc.rawMeans(),
            )
        }
        for (head in failedHeads) if (!scores.containsKey(head)) {
            scores[head] = DimScore(0f, 0f, 0f, 0f, 0, Engine.FAILED, emptyMap())
        }

        val dimMeans = dims.mapValues { it.value.welford.mean() }
        val odd = Fusion.composite(dimMeans, lastSettings)
        val weather = Fusion.weatherContext(rainMean.mean(), hazeMean.mean())

        val window = Window(
            enterTs = enterWallMs,
            exitTs = lastWallMs,
            sampleCounts = dims.mapValues { it.value.welford.n.toInt() },
            dutyCycleState = worstDuty.name,
            speedMeanMps = speed.mean(),
            speedMinMps = speed.minOrZero(),
            speedMaxMps = speed.maxOrZero(),
            distanceM = distanceM,
            frameCount = frameCount,
        )
        val gateList = gateCounts.map { (dim, v) -> GateRecord(dim, v.first, v.second) }

        return SegmentResult(
            key = key,
            segmentRef = SegmentRef(key.geohash7, key.headingBucket, centroidLat, centroidLon),
            window = window,
            timeBucket = timeBucket,
            weatherContext = weather,
            oddScore = odd,
            scores = scores,
            gates = gateList,
            capability = CapabilitySnapshot(rawGnssSupported, gnssCapability, lightSensorPresent),
            evidence = evidence.toList(),
        )
    }

    /** Live composite preview for the DRIVE screen (no finalisation). */
    fun liveOdd(): Float = Fusion.composite(dims.mapValues { it.value.welford.mean() }, lastSettings)
    fun liveDimMeans(): Map<String, Float> = dims.mapValues { it.value.welford.mean() }

    private fun bestCapability(a: GnssCapability, b: GnssCapability): GnssCapability {
        fun rank(c: GnssCapability) = when (c) {
            GnssCapability.RAW_MEASUREMENTS -> 2; GnssCapability.STATUS_ONLY -> 1; GnssCapability.NONE -> 0
        }
        return if (rank(b) > rank(a)) b else a
    }

    fun toSnapshot(): AccumulatorSnapshot = AccumulatorSnapshot(
        keyEnc = key.encode(),
        centroidLat = centroidLat, centroidLon = centroidLon,
        enterWallMs = enterWallMs, timeBucketOrd = timeBucket.ordinal,
        lastWallMs = lastWallMs, frameCount = frameCount, distanceM = distanceM,
        worstDutyOrd = worstDuty.ordinal, speedEnc = speed.encode(),
        rainEnc = rainMean.encode(), hazeEnc = hazeMean.encode(),
        rawGnss = rawGnssSupported, gnssCapOrd = gnssCapability.ordinal, lightSensor = lightSensorPresent,
        dims = dims.mapValues { (_, acc) ->
            DimSnapshot(acc.welford.encode(), acc.engine, acc.raw.mapValues { it.value.encode() })
        },
        gates = gateCounts.mapValues { GateSnapshot(it.value.first, it.value.second) },
        failed = failedHeads.toList(),
    )

    companion object {
        fun fromSnapshot(s: AccumulatorSnapshot): SegmentAccumulator {
            val acc = SegmentAccumulator(
                SegmentKey.decode(s.keyEnc), s.centroidLat, s.centroidLon, s.enterWallMs,
                TimeBucket.entries[s.timeBucketOrd],
            )
            acc.lastWallMs = s.lastWallMs; acc.frameCount = s.frameCount; acc.distanceM = s.distanceM
            acc.worstDuty = DutyCycleState.entries[s.worstDutyOrd]
            acc.speed = Welford.decode(s.speedEnc)
            acc.rainMean = RunningMean.decode(s.rainEnc); acc.hazeMean = RunningMean.decode(s.hazeEnc)
            acc.rawGnssSupported = s.rawGnss; acc.gnssCapability = GnssCapability.entries[s.gnssCapOrd]
            acc.lightSensorPresent = s.lightSensor
            for ((dim, ds) in s.dims) {
                val da = DimAcc(Welford.decode(ds.welfordEnc), ds.engine)
                for ((k, enc) in ds.rawMeans) da.raw[k] = RunningMean.decode(enc)
                acc.dims[dim] = da
            }
            for ((dim, gs) in s.gates) acc.gateCounts[dim] = Pair(gs.count, gs.reason)
            acc.failedHeads.addAll(s.failed)
            return acc
        }
    }
}

@Serializable
data class AccumulatorSnapshot(
    val keyEnc: String,
    val centroidLat: Double,
    val centroidLon: Double,
    val enterWallMs: Long,
    val timeBucketOrd: Int,
    val lastWallMs: Long,
    val frameCount: Int,
    val distanceM: Float,
    val worstDutyOrd: Int,
    val speedEnc: String,
    val rainEnc: String, val hazeEnc: String,
    val rawGnss: Boolean, val gnssCapOrd: Int, val lightSensor: Boolean,
    val dims: Map<String, DimSnapshot>,
    val gates: Map<String, GateSnapshot>,
    val failed: List<String>,
)

@Serializable
data class DimSnapshot(val welfordEnc: String, val engine: String, val rawMeans: Map<String, String>)

@Serializable
data class GateSnapshot(val count: Int, val reason: String)
