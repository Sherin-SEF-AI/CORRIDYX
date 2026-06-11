package ai.deepmost.corridyx.capture

import ai.deepmost.corridyx.config.Settings
import ai.deepmost.corridyx.dimensions.Dim
import ai.deepmost.corridyx.dimensions.FrameContext
import ai.deepmost.corridyx.dimensions.MetricSample
import ai.deepmost.corridyx.dimensions.OddDimension
import ai.deepmost.corridyx.dimensions.chaos.ChaosDimension
import ai.deepmost.corridyx.dimensions.glaryx.AtmoDimension
import ai.deepmost.corridyx.dimensions.glaryx.GlareDimension
import ai.deepmost.corridyx.dimensions.glaryx.LensDirtDimension
import ai.deepmost.corridyx.dimensions.glaryx.NightQDimension
import ai.deepmost.corridyx.dimensions.gnssq.GnssQDimension
import ai.deepmost.corridyx.dimensions.illum.IllumDimension
import ai.deepmost.corridyx.dimensions.lanevis.LaneVisDimension
import ai.deepmost.corridyx.dimensions.rough.RoughnessDimension
import ai.deepmost.corridyx.cv.AnalysisFrame
import ai.deepmost.corridyx.packet.EvidenceCandidate
import ai.deepmost.corridyx.registry.ModelRegistry
import ai.deepmost.corridyx.segment.AccumulatorActor
import ai.deepmost.corridyx.segment.FrameScoreBundle
import ai.deepmost.corridyx.segment.GeohashHeadingSegmenter
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The per-frame orchestrator. Runs on the CameraX analysis executor: throttles to the effective FPS
 * (duty-cycled), converts the frame once, fans out to enabled heads on a bounded dispatcher
 * (latest-frame-wins), builds the [FrameScoreBundle], submits it to the single-writer accumulator,
 * publishes overlay state, and evaluates driver alerts. No inference touches the main thread.
 */
class AnalysisPipeline(
    private val scope: CoroutineScope,
    registry: ModelRegistry,
    private val sensorHub: SensorHub,
    private val locationHub: LocationHub,
    private val dutyCycle: DutyCycleController,
    private val actor: AccumulatorActor,
    private val alerts: AlertController,
    private val settingsProvider: () -> Settings,
) {
    private val segmenter = GeohashHeadingSegmenter()

    // all heads constructed once; enable flags filter per tick
    private val lensHead = LensDirtDimension()
    private val glareHead = GlareDimension()
    private val roughHead = RoughnessDimension()
    private val heads: Map<String, OddDimension> = mapOf(
        Dim.LANE_VIS to LaneVisDimension(registry),
        Dim.GNSS_Q to GnssQDimension(),
        Dim.ILLUM to IllumDimension(),
        Dim.CHAOS to ChaosDimension(registry),
        Dim.ROUGH to roughHead,
        Dim.LENS to lensHead,
        Dim.GLARE to glareHead,
        Dim.ATMO to AtmoDimension(registry),
        Dim.NIGHTQ to NightQDimension(),
    )

    private var pool: FramePool? = null
    private val busy = AtomicBoolean(false)
    private var lastProcessedMs = 0L
    private var lastFrameNs = 0L

    private val _overlay = MutableStateFlow(OverlayState.EMPTY)
    val overlay: StateFlow<OverlayState> = _overlay.asStateFlow()

    /** CameraX analyzer entrypoint. */
    fun analyze(image: ImageProxy) {
        val settings = settingsProvider()
        val targetFps = dutyCycle.effectiveFps(settings.analysisFps, settings.thermalAggressiveness)
        val minIntervalMs = (1000L / targetFps).coerceAtLeast(1L)
        val now = System.currentTimeMillis()
        if (now - lastProcessedMs < minIntervalMs || !busy.compareAndSet(false, true)) {
            image.close(); return
        }
        lastProcessedMs = now
        val startNs = System.nanoTime()
        try {
            processFrame(image, settings, now)
        } catch (t: Throwable) {
            Timber.e(t, "analyze frame failed")
        } finally {
            image.close()
            dutyCycle.recordInferenceMs((System.nanoTime() - startNs) / 1_000_000L)
            busy.set(false)
        }
    }

    private fun processFrame(image: ImageProxy, settings: Settings, wallMs: Long) {
        // (re)build pool if dims changed
        val (ow, oh) = YuvConverter.outputDims(image, targetLongEdge = 480)
        var p = pool
        if (p == null || !p.matches(ow, oh)) { p = FramePool(ow, oh); pool = p }
        val frame = p.next()
        YuvConverter.fill(image, frame)
        p.commit(frame)

        val loc = locationHub.latestLocation
        val gnss = locationHub.latestGnss
        val imu = sensorHub.snapshot(1000)
        val lux = sensorHub.lightLux
        val sun = if (loc != null) SunCalc.position(wallMs, loc.lat, loc.lon) else null
        val timeBucket = sun?.let { SunCalc.bucket(it) } ?: TimeBucket.DAY
        val heading = if (loc?.hasBearing == true) loc.bearingDeg else null

        roughHead.minSpeedMps = settings.minSpeedMpsRoughness

        val ctx = FrameContext(
            frame = frame,
            prevFrame = p.previous,
            timestampNs = frame.timestampNs,
            wallClockMs = wallMs,
            location = loc,
            gnss = gnss,
            imu = imu,
            lightLux = lux,
            sun = sun,
            timeBucket = timeBucket,
            headingDeg = heading,
        )

        val enabled = enabledHeads(settings)
        val samples = runBlocking {
            enabled.map { head ->
                scope.async(Dispatchers.Default) { runCatching { head.process(ctx) }.getOrNull() }
            }.awaitAll()
        }.filterNotNull()

        // alerts + overlay from the GLARYX/conditions of this tick
        val pc = ai.deepmost.corridyx.conditions_api.PerceptionConditions.from(samples, wallMs)
        alerts.evaluate(pc, settings, wallMs)
        publishOverlay(frame, samples)

        // distance delta from speed * dt (GPS-jitter-free)
        val dtS = if (lastFrameNs > 0) ((frame.timestampNs - lastFrameNs).coerceAtLeast(0L)) / 1e9 else 0.0
        lastFrameNs = frame.timestampNs
        val speed = loc?.speedMps ?: 0f
        val distanceDelta = (speed * dtS).toFloat().coerceIn(0f, 100f)

        val segKey = loc?.let { segmenter.segmentFor(it) }
        val centroid = segKey?.let { segmenter.centroid(it) } ?: doubleArrayOf(loc?.lat ?: 0.0, loc?.lon ?: 0.0)

        val evidence = buildEvidence(frame, samples, settings)

        actor.submit(
            FrameScoreBundle(
                timestampNs = frame.timestampNs,
                wallMs = wallMs,
                segmentKey = segKey,
                centroidLat = centroid[0],
                centroidLon = centroid[1],
                timeBucket = timeBucket,
                samples = samples,
                speedMps = speed,
                distanceDeltaM = distanceDelta,
                dutyCycle = dutyCycle.state,
                rawGnssSupported = locationHub.capability == GnssCapability.RAW_MEASUREMENTS,
                gnssCapability = locationHub.capability,
                lightSensorPresent = sensorHub.lightSensorPresent,
                evidence = evidence,
            )
        )
    }

    private fun enabledHeads(s: Settings): List<OddDimension> = buildList {
        if (s.enableLaneVis) add(heads.getValue(Dim.LANE_VIS))
        if (s.enableGnssQ) add(heads.getValue(Dim.GNSS_Q))
        if (s.enableIllum) add(heads.getValue(Dim.ILLUM))
        if (s.enableChaos) add(heads.getValue(Dim.CHAOS))
        if (s.enableRough) add(heads.getValue(Dim.ROUGH))
        if (s.enableLens) add(heads.getValue(Dim.LENS))
        if (s.enableGlare) add(heads.getValue(Dim.GLARE))
        if (s.enableAtmo) add(heads.getValue(Dim.ATMO))
        if (s.enableNightQ) add(heads.getValue(Dim.NIGHTQ))
    }

    private fun publishOverlay(frame: AnalysisFrame, samples: List<MetricSample>) {
        // dominant degradation = lowest-scoring head this tick
        val dominant = samples.minByOrNull { it.score }?.dimension ?: ""
        _overlay.value = OverlayState(
            frameW = frame.width, frameH = frame.height,
            lensGridCols = lensHead.cols, lensGridRows = lensHead.rows,
            lensDirtMask = lensHead.lastDirtMask,
            glareBlobs = glareHead.lastBlobs,
            dominant = dominant,
        )
    }

    /** Capture a blurred-later evidence thumbnail only for EXTREME degradations (bounded, optional). */
    private fun buildEvidence(frame: AnalysisFrame, samples: List<MetricSample>, s: Settings): EvidenceCandidate? {
        if (!s.evidenceEnabled || s.evidenceMaxPerSegment <= 0) return null
        val worst = samples.minByOrNull { it.score } ?: return null
        // only attach for genuinely extreme frames so we illustrate the score, not every frame
        val reason: String = when {
            worst.dimension == Dim.LANE_VIS && worst.score < 0.18f -> "lane_markings_invisible"
            worst.dimension == Dim.GLARE && worst.score < 0.25f -> "sun_glare_blinding"
            worst.dimension == Dim.LENS && worst.score < 0.45f -> "lens_dirt"
            worst.dimension == Dim.ATMO && worst.score < 0.30f -> "heavy_rain_or_fog"
            worst.dimension == Dim.ILLUM && worst.score < 0.15f -> "underlit_corridor"
            else -> return null
        }
        val thumb = downscaleRgb(frame, s.evidenceThumbMaxDim)
        return EvidenceCandidate(
            width = thumb.w, height = thumb.h, rgb = thumb.rgb,
            dimension = worst.dimension, reason = reason, score = worst.score,
            vehicleBoxes = emptyList(),
        )
    }

    private class Thumb(val w: Int, val h: Int, val rgb: IntArray)
    private fun downscaleRgb(frame: AnalysisFrame, maxDim: Int): Thumb {
        val scale = maxDim.toFloat() / maxOf(frame.width, frame.height)
        if (scale >= 1f) return Thumb(frame.width, frame.height, frame.rgb.copyOf())
        val w = (frame.width * scale).toInt().coerceAtLeast(16)
        val h = (frame.height * scale).toInt().coerceAtLeast(16)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sy = (y / scale).toInt().coerceIn(0, frame.height - 1)
            for (x in 0 until w) {
                val sx = (x / scale).toInt().coerceIn(0, frame.width - 1)
                out[y * w + x] = frame.rgb[sy * frame.width + sx]
            }
        }
        return Thumb(w, h, out)
    }

    fun close() { heads.values.forEach { runCatching { it.close() } } }
}
