package ai.deepmost.corridyx.capture

import ai.deepmost.corridyx.segment.DutyCycleState
import android.content.Context
import android.os.Build
import android.os.PowerManager
import timber.log.Timber
import kotlin.math.max

/**
 * Thermal-aware duty cycling for shift-long (8-10h) capture without thermal collapse. Combines two
 * signals and records the resulting [DutyCycleState] in packets so aggregation can weight by it:
 *   1. PowerManager.getCurrentThermalStatus() (API 29+).
 *   2. An inference-time watchdog: an EMA of per-frame analysis time; if it exceeds a budget the
 *      effective FPS is throttled even when the OS hasn't raised thermal status yet.
 *
 * effectiveFps = baseFps scaled by a factor in {1.0, 0.6, 0.3} chosen by the worse of the two
 * signals, further scaled by [thermalAggressiveness] (0..2).
 */
class DutyCycleController(context: Context) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var inferEmaMs = 0.0
    @Volatile var state = DutyCycleState.NORMAL
        private set

    fun recordInferenceMs(ms: Long) {
        inferEmaMs = if (inferEmaMs == 0.0) ms.toDouble() else 0.8 * inferEmaMs + 0.2 * ms
    }

    /**
     * @return the effective analysis FPS to target right now, in [1, baseFps].
     */
    fun effectiveFps(baseFps: Int, thermalAggressiveness: Float, frameBudgetMs: Long = 140L): Int {
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else PowerManager.THERMAL_STATUS_NONE
        val thermalFactor = when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> 0.3f
            thermal >= PowerManager.THERMAL_STATUS_MODERATE -> 0.6f
            else -> 1.0f
        }
        val budget = frameBudgetMs / max(0.25f, thermalAggressiveness)
        val watchdogFactor = when {
            inferEmaMs > budget * 2 -> 0.3f
            inferEmaMs > budget -> 0.6f
            else -> 1.0f
        }
        val factor = minOf(thermalFactor, watchdogFactor)
        val newState = when {
            factor <= 0.3f -> DutyCycleState.THROTTLED
            factor <= 0.6f -> DutyCycleState.REDUCED
            else -> DutyCycleState.NORMAL
        }
        if (newState != state) {
            Timber.i("Duty cycle %s -> %s (thermal=%d inferEma=%.0fms)", state, newState, thermal, inferEmaMs)
            state = newState
        }
        return max(1, (baseFps * factor).toInt())
    }
}
