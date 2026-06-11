package ai.deepmost.corridyx.service

import ai.deepmost.corridyx.AppGraph
import ai.deepmost.corridyx.CorridyxApp
import ai.deepmost.corridyx.R
import ai.deepmost.corridyx.capture.AlertController
import ai.deepmost.corridyx.capture.AnalysisPipeline
import ai.deepmost.corridyx.capture.DutyCycleController
import ai.deepmost.corridyx.capture.LocationHub
import ai.deepmost.corridyx.capture.SensorHub
import ai.deepmost.corridyx.conditions_api.PerceptionConditionsBus
import ai.deepmost.corridyx.packet.PacketAssembler
import ai.deepmost.corridyx.packet.StorageManager
import ai.deepmost.corridyx.packet.db.SessionEntity
import ai.deepmost.corridyx.privacy.RegionBlurrer
import ai.deepmost.corridyx.registry.ModelRegistry
import ai.deepmost.corridyx.segment.AccumulatorActor
import ai.deepmost.corridyx.segment.AccumulatorSnapshotStore
import ai.deepmost.corridyx.segment.SegmentResult
import ai.deepmost.corridyx.config.Settings
import ai.deepmost.corridyx.upload.UploadScheduler
import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Shift-long foreground capture service. Owns the camera (ImageAnalysis for scoring + an optional
 * Preview the UI attaches while visible), the sensor/location hubs, the analysis pipeline, the
 * single-writer accumulator, and the packet assembler. Screen-off safe (partial wakelock); battery-
 * conscious (analysis FPS, never video). Also keeps the PerceptionConditions bus live for siblings.
 */
class CaptureService : LifecycleService() {

    private lateinit var graph: AppGraph
    private lateinit var sensorHub: SensorHub
    private lateinit var locationHub: LocationHub
    private lateinit var dutyCycle: DutyCycleController
    private lateinit var alerts: AlertController
    private lateinit var registry: ModelRegistry
    private lateinit var blurrer: RegionBlurrer
    private lateinit var actor: AccumulatorActor
    private lateinit var assembler: PacketAssembler
    private lateinit var pipeline: AnalysisPipeline
    private lateinit var analysisExecutor: ExecutorService
    private var wakeLock: PowerManager.WakeLock? = null
    private var preview: Preview? = null

    private var sessionId: String = ""
    private var sessionDistanceM = 0f
    private var segmentsVisited = 0
    private var started = false

    /** Live settings cache (collected once) — reading DataStore per analysis frame would hit disk. */
    @Volatile private var cachedSettings: Settings = Settings.DEFAULT

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopCapture(); return START_NOT_STICKY }
            else -> if (!started) startCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        started = true
        graph = AppGraph.get(this)
        startForegroundWithType(buildNotification(0f, "starting"))

        sessionId = UUID.randomUUID().toString()
        CaptureController.currentSessionId = sessionId
        CaptureController.setRunning(true)
        PerceptionConditionsBus.capturing = true

        // keep a hot copy of settings so the analysis hot path never touches DataStore/disk
        cachedSettings = runBlocking { graph.settingsRepository.flow.first() }
        lifecycleScope.launch { graph.settingsRepository.flow.collect { cachedSettings = it } }

        analysisExecutor = Executors.newSingleThreadExecutor()
        sensorHub = SensorHub(this).also { it.start() }
        locationHub = LocationHub(this).also { it.start() }
        dutyCycle = DutyCycleController(this)
        alerts = AlertController(this).also { it.initTts(this) }
        registry = ModelRegistry(this).also { it.initialize() }
        blurrer = RegionBlurrer()

        val storage = StorageManager(graph.db.packets()) { cachedSettings }
        val snapshotStore = AccumulatorSnapshotStore(this)
        assembler = PacketAssembler(
            context = this,
            dao = graph.db.packets(),
            blurrer = blurrer,
            storage = storage,
            nodeId = { resolveNodeId() },
            modelVersions = { registry.versionMap() },
        )

        actor = AccumulatorActor(
            scope = lifecycleScope,
            sessionId = sessionId,
            settingsProvider = { cachedSettings },
            snapshotStore = snapshotStore,
            onFinalized = ::onSegmentFinalized,
        ).also { it.start() }

        pipeline = AnalysisPipeline(
            scope = lifecycleScope,
            registry = registry,
            sensorHub = sensorHub,
            locationHub = locationHub,
            dutyCycle = dutyCycle,
            actor = actor,
            alerts = alerts,
            settingsProvider = { cachedSettings },
        )

        acquireWakeLock()
        observeStreams()
        createSessionRow()
        startCamera()
        Timber.i("Capture session %s started", sessionId)
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(analysisExecutor) { image -> pipeline.analyze(image) } }
            val pv = Preview.Builder().build()
            preview = pv
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis, pv)
                // attach/detach the on-screen preview as the DRIVE screen comes and goes
                lifecycleScope.launch {
                    CaptureController.surface.collect { sp -> pv.setSurfaceProvider(sp) }
                }
            } catch (t: Throwable) {
                Timber.e(t, "camera bind failed")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun observeStreams() {
        lifecycleScope.launch { actor.live.collect { CaptureController.publishLive(it) } }
        lifecycleScope.launch { pipeline.overlay.collect { CaptureController.publishOverlay(it) } }
        lifecycleScope.launch {
            alerts.alert.collect {
                CaptureController.publishAlert(it)
                updateNotification()
            }
        }
    }

    /**
     * Called from the accumulator coroutine (Dispatchers.Default) and AWAITED, so packet assembly
     * (blur + JPEG + Room insert) completes before the next bundle is processed and, crucially,
     * before [teardown] closes the blurrer/registry at shutdown.
     */
    private suspend fun onSegmentFinalized(result: SegmentResult, session: String) {
        sessionDistanceM += result.window.distanceM
        segmentsVisited++
        runCatching { assembler.assemble(result, session) }
            .onFailure { Timber.e(it, "assemble failed") }
        UploadScheduler.schedule(this@CaptureService, cachedSettings)
        updateSessionRow()
        updateNotification()
    }

    private fun createSessionRow() {
        lifecycleScope.launch(Dispatchers.IO) {
            val settings = graph.settingsRepository.flow.first()
            graph.db.sessions().upsert(
                SessionEntity(
                    sessionId = sessionId,
                    nodeId = resolveNodeId(),
                    vehicleLabel = settings.vehicleLabel,
                    startTs = System.currentTimeMillis(),
                    endTs = null,
                    distanceM = 0f,
                    segmentsVisited = 0,
                    alertsRaised = 0,
                )
            )
        }
    }

    private suspend fun updateSessionRow() {
        graph.db.sessions().finalizeSession(
            sessionId, System.currentTimeMillis(), sessionDistanceM, segmentsVisited, alerts.totalAlerts
        )
    }

    private fun stopCapture() {
        if (!started) { stopSelf(); return }
        started = false
        Timber.i("Stopping capture session %s", sessionId)
        // Finalize + assemble the in-flight segment synchronously (off-main) BEFORE teardown closes
        // the blurrer/registry it needs.
        runBlocking(Dispatchers.Default) {
            runCatching { actor.flushAndFinalize() }.onFailure { Timber.e(it, "final flush failed") }
            runCatching { updateSessionRow() }
            UploadScheduler.schedule(this@CaptureService, cachedSettings)
        }
        teardown()
        CaptureController.setRunning(false)
        PerceptionConditionsBus.capturing = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        runCatching { ProcessCameraProvider.getInstance(this).get().unbindAll() }
        runCatching { sensorHub.stop() }
        runCatching { locationHub.stop() }
        runCatching { pipeline.close() }
        runCatching { actor.close() }
        runCatching { registry.close() }
        runCatching { blurrer.close() }
        runCatching { alerts.shutdown() }
        runCatching { analysisExecutor.shutdown() }
        releaseWakeLock()
    }

    private fun resolveNodeId(): String =
        cachedSettings.nodeId.ifBlank { "node-" + (Build.MODEL ?: "device").replace(' ', '_') }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "corridyx:capture").apply { acquire() }
    }

    private fun releaseWakeLock() { runCatching { wakeLock?.release() }; wakeLock = null }

    private fun updateNotification() {
        val live = CaptureController.live.value
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(live.liveOdd, live.segmentId ?: "no-fix"))
    }

    private fun buildNotification(odd: Float, segment: String): Notification {
        return NotificationCompat.Builder(this, CorridyxApp.CH_CAPTURE)
            .setContentTitle("CORRIDYX scoring corridor")
            .setContentText("ODD ${odd.toInt()} • seg $segment • ${segmentsVisited} finalized")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        if (started) stopCapture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "ai.deepmost.corridyx.START"
        const val ACTION_STOP = "ai.deepmost.corridyx.STOP"
        private const val NOTIF_ID = 4201
    }
}
