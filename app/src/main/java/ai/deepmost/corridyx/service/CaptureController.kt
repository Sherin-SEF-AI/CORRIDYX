package ai.deepmost.corridyx.service

import ai.deepmost.corridyx.capture.DriverAlert
import ai.deepmost.corridyx.capture.OverlayState
import ai.deepmost.corridyx.segment.LiveSnapshot
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.camera.core.Preview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide bridge between the [CaptureService] (which owns the camera + pipeline for screen-off
 * shift-long capture) and the Compose UI. The UI observes these flows and hands the service a
 * PreviewView surface provider only while the DRIVE screen is visible (capture keeps running with
 * the screen off — only the on-screen preview pauses).
 */
object CaptureController {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _live = MutableStateFlow(LiveSnapshot.INITIAL)
    val live: StateFlow<LiveSnapshot> = _live.asStateFlow()

    private val _overlay = MutableStateFlow(OverlayState.EMPTY)
    val overlay: StateFlow<OverlayState> = _overlay.asStateFlow()

    private val _alert = MutableStateFlow<DriverAlert?>(null)
    val alert: StateFlow<DriverAlert?> = _alert.asStateFlow()

    private val _surface = MutableStateFlow<Preview.SurfaceProvider?>(null)
    val surface: StateFlow<Preview.SurfaceProvider?> = _surface.asStateFlow()

    @Volatile var currentSessionId: String? = null

    // --- service -> controller ---
    internal fun setRunning(v: Boolean) { _running.value = v }
    internal fun publishLive(s: LiveSnapshot) { _live.value = s }
    internal fun publishOverlay(o: OverlayState) { _overlay.value = o }
    internal fun publishAlert(a: DriverAlert?) { _alert.value = a }

    // --- UI -> controller ---
    fun setSurfaceProvider(p: Preview.SurfaceProvider?) { _surface.value = p }

    fun start(context: Context) {
        val intent = Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
    }
}
