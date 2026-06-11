package ai.deepmost.corridyx.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "corridyx_settings")

/**
 * DataStore-backed settings. Exposes a [Flow] of [Settings] for the whole app and a set of
 * coroutine setters used by the SETTINGS screen. Every key has a default sourced from
 * [Settings.DEFAULT] so a fresh install is fully runnable.
 */
class SettingsRepository(private val context: Context) {

    private object K {
        val analysisFps = intPreferencesKey("analysisFps")
        val minSpeedRough = floatPreferencesKey("minSpeedMpsRoughness")
        val minSpeedAccum = floatPreferencesKey("minSpeedMpsAccumulate")
        val hystM = floatPreferencesKey("segHystM")
        val hystF = intPreferencesKey("segHystF")

        val enLane = booleanPreferencesKey("enLane")
        val enGnss = booleanPreferencesKey("enGnss")
        val enIllum = booleanPreferencesKey("enIllum")
        val enChaos = booleanPreferencesKey("enChaos")
        val enRough = booleanPreferencesKey("enRough")
        val enLens = booleanPreferencesKey("enLens")
        val enGlare = booleanPreferencesKey("enGlare")
        val enAtmo = booleanPreferencesKey("enAtmo")
        val enNight = booleanPreferencesKey("enNight")

        val wLane = floatPreferencesKey("wLane")
        val wGnss = floatPreferencesKey("wGnss")
        val wIllum = floatPreferencesKey("wIllum")
        val wChaos = floatPreferencesKey("wChaos")
        val wRough = floatPreferencesKey("wRough")
        val wPerc = floatPreferencesKey("wPerc")

        val gLensLane = floatPreferencesKey("gLensLane")
        val gGlareLane = floatPreferencesKey("gGlareLane")
        val gMountRough = floatPreferencesKey("gMountRough")

        val aLens = floatPreferencesKey("aLens")
        val aGlare = floatPreferencesKey("aGlare")
        val aRain = floatPreferencesKey("aRain")
        val aCooldown = longPreferencesKey("aCooldown")
        val tts = booleanPreferencesKey("tts")

        val evOn = booleanPreferencesKey("evOn")
        val evMax = intPreferencesKey("evMax")
        val evDim = intPreferencesKey("evDim")

        val upEndpoint = stringPreferencesKey("upEndpoint")
        val upToken = stringPreferencesKey("upToken")
        val upWifi = booleanPreferencesKey("upWifi")
        val nodeId = stringPreferencesKey("nodeId")
        val vehLabel = stringPreferencesKey("vehLabel")

        val capMb = intPreferencesKey("capMb")
        val thermal = floatPreferencesKey("thermal")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        val d = Settings.DEFAULT
        Settings(
            analysisFps = p[K.analysisFps] ?: d.analysisFps,
            minSpeedMpsRoughness = p[K.minSpeedRough] ?: d.minSpeedMpsRoughness,
            minSpeedMpsAccumulate = p[K.minSpeedAccum] ?: d.minSpeedMpsAccumulate,
            segmentHysteresisMeters = p[K.hystM] ?: d.segmentHysteresisMeters,
            segmentHysteresisFrames = p[K.hystF] ?: d.segmentHysteresisFrames,
            enableLaneVis = p[K.enLane] ?: d.enableLaneVis,
            enableGnssQ = p[K.enGnss] ?: d.enableGnssQ,
            enableIllum = p[K.enIllum] ?: d.enableIllum,
            enableChaos = p[K.enChaos] ?: d.enableChaos,
            enableRough = p[K.enRough] ?: d.enableRough,
            enableLens = p[K.enLens] ?: d.enableLens,
            enableGlare = p[K.enGlare] ?: d.enableGlare,
            enableAtmo = p[K.enAtmo] ?: d.enableAtmo,
            enableNightQ = p[K.enNight] ?: d.enableNightQ,
            wLaneVis = p[K.wLane] ?: d.wLaneVis,
            wGnssQ = p[K.wGnss] ?: d.wGnssQ,
            wIllum = p[K.wIllum] ?: d.wIllum,
            wChaos = p[K.wChaos] ?: d.wChaos,
            wRough = p[K.wRough] ?: d.wRough,
            wPerception = p[K.wPerc] ?: d.wPerception,
            gateLensDirtInvalidatesLaneVis = p[K.gLensLane] ?: d.gateLensDirtInvalidatesLaneVis,
            gateGlareInvalidatesLaneVis = p[K.gGlareLane] ?: d.gateGlareInvalidatesLaneVis,
            gateMountVibInvalidatesRough = p[K.gMountRough] ?: d.gateMountVibInvalidatesRough,
            alertLensDirt = p[K.aLens] ?: d.alertLensDirt,
            alertGlare = p[K.aGlare] ?: d.alertGlare,
            alertHeavyRain = p[K.aRain] ?: d.alertHeavyRain,
            alertCooldownMs = p[K.aCooldown] ?: d.alertCooldownMs,
            ttsAlerts = p[K.tts] ?: d.ttsAlerts,
            evidenceEnabled = p[K.evOn] ?: d.evidenceEnabled,
            evidenceMaxPerSegment = p[K.evMax] ?: d.evidenceMaxPerSegment,
            evidenceThumbMaxDim = p[K.evDim] ?: d.evidenceThumbMaxDim,
            uploadEndpoint = p[K.upEndpoint] ?: d.uploadEndpoint,
            uploadToken = p[K.upToken] ?: d.uploadToken,
            uploadWifiOnly = p[K.upWifi] ?: d.uploadWifiOnly,
            nodeId = p[K.nodeId] ?: d.nodeId,
            vehicleLabel = p[K.vehLabel] ?: d.vehicleLabel,
            storageCapMb = p[K.capMb] ?: d.storageCapMb,
            thermalAggressiveness = p[K.thermal] ?: d.thermalAggressiveness,
        )
    }

    suspend fun update(block: SettingsEditor.() -> Unit) {
        context.dataStore.edit { p ->
            SettingsEditor(p).apply(block)
        }
    }

    /** Thin typed editor so the UI writes intention-revealing setters. */
    class SettingsEditor(private val p: androidx.datastore.preferences.core.MutablePreferences) {
        fun analysisFps(v: Int) { p[K.analysisFps] = v.coerceIn(1, 15) }
        fun minSpeedRoughness(v: Float) { p[K.minSpeedRough] = v }
        fun minSpeedAccumulate(v: Float) { p[K.minSpeedAccum] = v }
        fun hysteresisMeters(v: Float) { p[K.hystM] = v }
        fun hysteresisFrames(v: Int) { p[K.hystF] = v }

        fun enable(dim: String, v: Boolean) {
            val key = when (dim) {
                "lane_vis" -> K.enLane; "gnss_q" -> K.enGnss; "illum" -> K.enIllum
                "chaos" -> K.enChaos; "rough" -> K.enRough; "lens" -> K.enLens
                "glare" -> K.enGlare; "atmo" -> K.enAtmo; "nightq" -> K.enNight
                else -> return
            }
            p[key] = v
        }

        fun weight(dim: String, v: Float) {
            val key = when (dim) {
                "lane_vis" -> K.wLane; "gnss_q" -> K.wGnss; "illum" -> K.wIllum
                "chaos" -> K.wChaos; "rough" -> K.wRough; "perception" -> K.wPerc
                else -> return
            }
            p[key] = v.coerceIn(0f, 1f)
        }

        fun gateLensLane(v: Float) { p[K.gLensLane] = v }
        fun gateGlareLane(v: Float) { p[K.gGlareLane] = v }
        fun gateMountRough(v: Float) { p[K.gMountRough] = v }

        fun alertLens(v: Float) { p[K.aLens] = v }
        fun alertGlare(v: Float) { p[K.aGlare] = v }
        fun alertRain(v: Float) { p[K.aRain] = v }
        fun alertCooldownMs(v: Long) { p[K.aCooldown] = v }
        fun tts(v: Boolean) { p[K.tts] = v }

        fun evidenceEnabled(v: Boolean) { p[K.evOn] = v }
        fun evidenceMax(v: Int) { p[K.evMax] = v.coerceIn(0, 4) }
        fun evidenceDim(v: Int) { p[K.evDim] = v.coerceIn(96, 640) }

        fun uploadEndpoint(v: String) { p[K.upEndpoint] = v }
        fun uploadToken(v: String) { p[K.upToken] = v }
        fun uploadWifiOnly(v: Boolean) { p[K.upWifi] = v }
        fun nodeId(v: String) { p[K.nodeId] = v }
        fun vehicleLabel(v: String) { p[K.vehLabel] = v }

        fun storageCapMb(v: Int) { p[K.capMb] = v.coerceIn(64, 8192) }
        fun thermalAggressiveness(v: Float) { p[K.thermal] = v.coerceIn(0f, 2f) }
    }
}
