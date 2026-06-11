package ai.deepmost.corridyx.capture

import ai.deepmost.corridyx.conditions_api.PerceptionConditions
import ai.deepmost.corridyx.config.Settings
import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.Locale

enum class AlertType { CLEAN_LENS, SUN_BLINDED, HEAVY_RAIN }

data class DriverAlert(val type: AlertType, val message: String, val tsMs: Long)

/**
 * Rate-limited driver alerts — the highest-value live interventions. Each type has an independent
 * cooldown so a persistently dirty lens doesn't spam. Optional offline TTS. Exposes the current
 * banner alert as a Flow and counts total alerts for the session summary.
 */
class AlertController(context: Context) {

    private val _alert = MutableStateFlow<DriverAlert?>(null)
    val alert: StateFlow<DriverAlert?> = _alert.asStateFlow()

    @Volatile var totalAlerts = 0
        private set

    private val lastFired = HashMap<AlertType, Long>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun initTts(context: Context) {
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.US
        }
    }

    fun evaluate(c: PerceptionConditions, s: Settings, nowMs: Long) {
        val candidate: DriverAlert = when {
            c.lens >= s.alertLensDirt -> DriverAlert(AlertType.CLEAN_LENS, "CLEAN LENS", nowMs)
            c.glare >= s.alertGlare -> DriverAlert(AlertType.SUN_BLINDED, "CAMERA BLINDED — sun", nowMs)
            c.atmo >= s.alertHeavyRain -> DriverAlert(AlertType.HEAVY_RAIN, "HEAVY RAIN — vision degraded", nowMs)
            else -> null
        } ?: run {
            // clear the banner if conditions have recovered well below threshold
            val cur = _alert.value
            if (cur != null && nowMs - cur.tsMs > s.alertCooldownMs) _alert.value = null
            return
        }

        val last = lastFired[candidate.type] ?: 0L
        if (nowMs - last < s.alertCooldownMs) return
        lastFired[candidate.type] = nowMs
        totalAlerts++
        _alert.value = candidate
        Timber.i("Driver alert: %s", candidate.message)
        if (s.ttsAlerts && ttsReady) {
            tts?.speak(candidate.message, TextToSpeech.QUEUE_FLUSH, null, candidate.type.name)
        }
    }

    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
    }
}
