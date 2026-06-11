package ai.deepmost.corridyx.segment

import ai.deepmost.corridyx.conditions_api.PerceptionConditions
import ai.deepmost.corridyx.config.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The single-writer accumulator actor. ALL metric samples funnel through one coroutine, so the
 * active [SegmentAccumulator], segment transitions (with hysteresis), Welford updates and
 * finalisation need no locks. Publishes a ~live [LiveSnapshot] for UI and a live
 * [PerceptionConditions] for the bound API (kept alive even during GPS gaps).
 */
class AccumulatorActor(
    private val scope: CoroutineScope,
    private val sessionId: String,
    private val settingsProvider: () -> Settings,
    private val snapshotStore: AccumulatorSnapshotStore,
    private val onFinalized: suspend (SegmentResult, String) -> Unit,
) {
    private val channel = Channel<FrameScoreBundle>(capacity = 128)
    private val doneSignal = kotlinx.coroutines.CompletableDeferred<Unit>()

    private val _live = MutableStateFlow(LiveSnapshot.INITIAL)
    val live: StateFlow<LiveSnapshot> = _live.asStateFlow()

    private val _conditions = MutableStateFlow(PerceptionConditions.UNKNOWN)
    val conditions: StateFlow<PerceptionConditions> = _conditions.asStateFlow()

    private var active: SegmentAccumulator? = null
    private var candidateKey: SegmentKey? = null
    private var candidateFrames = 0
    private var candidateDist = 0f
    private var framesSinceSnapshot = 0

    fun start() {
        // resume an in-flight segment after process death
        snapshotStore.load()?.let {
            active = runCatching { SegmentAccumulator.fromSnapshot(it) }.getOrNull()
            if (active != null) Timber.i("Resumed active segment %s after process death", active!!.key.encode())
        }
        // Off the main thread: Welford updates are cheap but finalization awaits packet assembly
        // (face-blur + JPEG), which must not run on Main.
        scope.launch(Dispatchers.Default) {
            for (bundle in channel) {
                runCatching { handle(bundle) }
                    .onFailure { Timber.e(it, "accumulator failed on bundle") }
            }
            // Channel closed (session end): finalize the in-flight segment HERE, on the same single
            // writer, after all buffered bundles have drained — then signal flushAndFinalize().
            active?.let {
                val result = it.finalize()
                runCatching { onFinalized(result, sessionId) }
                    .onFailure { e -> Timber.e(e, "session-end finalize failed") }
                Timber.i("Session-end finalize of segment %s (frames=%d)", it.key.encode(), it.frames())
            }
            active = null
            snapshotStore.clear()
            doneSignal.complete(Unit)
        }
    }

    /** Non-blocking offer from the analysis executor; drops to live-only if the channel is full. */
    fun submit(bundle: FrameScoreBundle) {
        val res = channel.trySend(bundle)
        if (res.isFailure) Timber.w("accumulator channel full — bundle dropped (live UI still updates)")
    }

    /** Stops ingestion, drains buffered bundles, finalizes the active segment, and awaits completion. */
    suspend fun flushAndFinalize() {
        channel.close()
        doneSignal.await()
    }

    fun close() { runCatching { channel.close() } }

    private suspend fun handle(bundle: FrameScoreBundle) {
        val settings = settingsProvider()

        // PerceptionConditions stays live regardless of GPS.
        val pc = PerceptionConditions.from(bundle.samples, bundle.wallMs)
        _conditions.value = pc
        ai.deepmost.corridyx.conditions_api.PerceptionConditionsBus.publish(pc)

        val key = bundle.segmentKey
        if (key == null) {
            // GPS gap: no accumulation; reflect a gap in the live snapshot.
            publishLive(bundle, pc, gpsValid = false)
            return
        }

        val cur = active
        when {
            cur == null -> {
                active = SegmentAccumulator(key, bundle.centroidLat, bundle.centroidLon, bundle.wallMs, bundle.timeBucket)
                    .also { it.add(bundle, settings) }
                candidateKey = null
            }
            key == cur.key -> {
                cur.add(bundle, settings)
                candidateKey = null; candidateFrames = 0; candidateDist = 0f
            }
            else -> {
                // hysteresis before declaring a segment exit
                if (candidateKey == key) {
                    candidateFrames++; candidateDist += bundle.distanceDeltaM
                } else {
                    candidateKey = key; candidateFrames = 1; candidateDist = bundle.distanceDeltaM
                }
                if (candidateFrames >= settings.segmentHysteresisFrames &&
                    candidateDist >= settings.segmentHysteresisMeters
                ) {
                    val result = cur.finalize()
                    onFinalized(result, sessionId)
                    Timber.i("Finalized segment %s (frames=%d, odd=%.1f)", cur.key.encode(), cur.frames(), result.oddScore)
                    active = SegmentAccumulator(key, bundle.centroidLat, bundle.centroidLon, bundle.wallMs, bundle.timeBucket)
                        .also { it.add(bundle, settings) }
                    candidateKey = null; candidateFrames = 0; candidateDist = 0f
                    snapshotStore.clear()
                } else {
                    // still near boundary: keep scoring the current segment
                    cur.add(bundle, settings)
                }
            }
        }

        // incremental snapshot for process-death safety
        if (++framesSinceSnapshot >= SNAPSHOT_EVERY) {
            framesSinceSnapshot = 0
            active?.let { snapshotStore.save(it.toSnapshot()) }
        }

        publishLive(bundle, pc, gpsValid = true)
    }

    private fun publishLive(bundle: FrameScoreBundle, pc: PerceptionConditions, gpsValid: Boolean) {
        val cur = active
        _live.value = LiveSnapshot(
            segmentId = if (gpsValid) cur?.key?.encode() else null,
            timeBucket = bundle.timeBucket,
            liveOdd = cur?.liveOdd() ?: 0f,
            dimMeans = cur?.liveDimMeans() ?: emptyMap(),
            instantSamples = bundle.samples.associate { it.dimension to it.score },
            conditions = pc,
            dutyCycle = bundle.dutyCycle,
            speedMps = bundle.speedMps,
            gpsValid = gpsValid,
            frameCount = cur?.frames() ?: 0,
        )
    }

    private companion object { const val SNAPSHOT_EVERY = 40 }
}
