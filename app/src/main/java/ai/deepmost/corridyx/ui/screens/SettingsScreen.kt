package ai.deepmost.corridyx.ui.screens

import ai.deepmost.corridyx.dimensions.Dim
import ai.deepmost.corridyx.ui.CorridyxViewModel
import ai.deepmost.corridyx.ui.Panel
import ai.deepmost.corridyx.ui.dimLabel
import ai.deepmost.corridyx.ui.theme.Accent
import ai.deepmost.corridyx.ui.theme.Bg
import ai.deepmost.corridyx.ui.theme.TextDim
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(vm: CorridyxViewModel) {
    val s by vm.settings.collectAsState()

    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(10.dp)) {
        Text("SETTINGS", color = Accent, style = MaterialTheme.typography.titleMedium)

        Section("CAPTURE") {
            LabeledSlider("analysis FPS", s.analysisFps.toFloat(), 1f..15f, steps = 13, format = { "${it.toInt()}" }) {
                vm.edit { analysisFps(it.toInt()) }
            }
            LabeledSlider("min speed — accumulate (m/s)", s.minSpeedMpsAccumulate, 0f..5f, format = { "%.1f".format(it) }) {
                vm.edit { minSpeedAccumulate(it) }
            }
            LabeledSlider("min speed — roughness (m/s)", s.minSpeedMpsRoughness, 0f..10f, format = { "%.1f".format(it) }) {
                vm.edit { minSpeedRoughness(it) }
            }
            LabeledSlider("thermal duty aggressiveness", s.thermalAggressiveness, 0f..2f, format = { "%.2f".format(it) }) {
                vm.edit { thermalAggressiveness(it) }
            }
        }

        Section("SEGMENTATION") {
            LabeledSlider("hysteresis distance (m)", s.segmentHysteresisMeters, 5f..100f, format = { "%.0f".format(it) }) {
                vm.edit { hysteresisMeters(it) }
            }
            LabeledSlider("hysteresis frames", s.segmentHysteresisFrames.toFloat(), 1f..30f, format = { "${it.toInt()}" }) {
                vm.edit { hysteresisFrames(it.toInt()) }
            }
        }

        Section("DIMENSIONS — enable") {
            DimToggle(vm, Dim.LANE_VIS, s.enableLaneVis)
            DimToggle(vm, Dim.GNSS_Q, s.enableGnssQ)
            DimToggle(vm, Dim.ILLUM, s.enableIllum)
            DimToggle(vm, Dim.CHAOS, s.enableChaos)
            DimToggle(vm, Dim.ROUGH, s.enableRough)
            DimToggle(vm, Dim.LENS, s.enableLens)
            DimToggle(vm, Dim.GLARE, s.enableGlare)
            DimToggle(vm, Dim.ATMO, s.enableAtmo)
            DimToggle(vm, Dim.NIGHTQ, s.enableNightQ)
        }

        Section("COMPOSITE WEIGHTS") {
            LabeledSlider("lane markings", s.wLaneVis, 0f..1f, format = { "%.2f".format(it) }) { vm.edit { weight("lane_vis", it) } }
            LabeledSlider("gnss quality", s.wGnssQ, 0f..1f, format = { "%.2f".format(it) }) { vm.edit { weight("gnss_q", it) } }
            LabeledSlider("illumination", s.wIllum, 0f..1f, format = { "%.2f".format(it) }) { vm.edit { weight("illum", it) } }
            LabeledSlider("agent chaos", s.wChaos, 0f..1f, format = { "%.2f".format(it) }) { vm.edit { weight("chaos", it) } }
            LabeledSlider("road roughness", s.wRough, 0f..1f, format = { "%.2f".format(it) }) { vm.edit { weight("rough", it) } }
            LabeledSlider("perception (GLARYX)", s.wPerception, 0f..1f, format = { "%.2f".format(it) }) { vm.edit { weight("perception", it) } }
        }

        Section("SENSOR-FAULT GATES") {
            LabeledSlider("lens dirt invalidates lane_vis", s.gateLensDirtInvalidatesLaneVis, 0f..1f, format = { "%.2f".format(it) }) { vm.edit { gateLensLane(it) } }
            LabeledSlider("glare invalidates lane_vis", s.gateGlareInvalidatesLaneVis, 0f..1f, format = { "%.2f".format(it) }) { vm.edit { gateGlareLane(it) } }
            LabeledSlider("mount-vib invalidates rough", s.gateMountVibInvalidatesRough, 0f..1f, format = { "%.2f".format(it) }) { vm.edit { gateMountRough(it) } }
        }

        Section("DRIVER ALERTS") {
            LabeledSlider("lens dirt alert", s.alertLensDirt, 0f..1f, format = { "%.2f".format(it) }) { vm.edit { alertLens(it) } }
            LabeledSlider("glare alert", s.alertGlare, 0f..1f, format = { "%.2f".format(it) }) { vm.edit { alertGlare(it) } }
            LabeledSlider("heavy-rain alert", s.alertHeavyRain, 0f..1f, format = { "%.2f".format(it) }) { vm.edit { alertRain(it) } }
            LabeledSwitch("spoken alerts (TTS)", s.ttsAlerts) { c -> vm.edit { tts(c) } }
        }

        Section("EVIDENCE THUMBNAILS") {
            LabeledSwitch("attach evidence (blurred)", s.evidenceEnabled) { c -> vm.edit { evidenceEnabled(c) } }
            LabeledSlider("max per segment", s.evidenceMaxPerSegment.toFloat(), 0f..4f, steps = 3, format = { "${it.toInt()}" }) { vm.edit { evidenceMax(it.toInt()) } }
            LabeledSlider("thumbnail longest edge (px)", s.evidenceThumbMaxDim.toFloat(), 96f..640f, format = { "${it.toInt()}" }) { vm.edit { evidenceDim(it.toInt()) } }
            Text("Faces + number plates are always blurred before any thumbnail is stored or sent.", color = TextDim, style = MaterialTheme.typography.labelSmall)
        }

        Section("STORAGE") {
            LabeledSlider("storage cap (MB)", s.storageCapMb.toFloat(), 64f..4096f, format = { "${it.toInt()}" }) { vm.edit { storageCapMb(it.toInt()) } }
            Text("Over cap: oldest UPLOADED evidence is evicted first; manifests are never evicted.", color = TextDim, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Panel(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column {
            Text(title, color = TextDim, style = MaterialTheme.typography.labelSmall)
            content()
        }
    }
}

@Composable
private fun DimToggle(vm: CorridyxViewModel, dim: String, enabled: Boolean) {
    LabeledSwitch(dimLabel(dim), enabled) { c -> vm.edit { enable(dim, c) } }
}
