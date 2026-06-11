package ai.deepmost.corridyx.ui.screens

import ai.deepmost.corridyx.registry.Accelerator
import ai.deepmost.corridyx.registry.EngineStatus
import ai.deepmost.corridyx.ui.CorridyxViewModel
import ai.deepmost.corridyx.ui.Hairline
import ai.deepmost.corridyx.ui.Panel
import ai.deepmost.corridyx.ui.StatRow
import ai.deepmost.corridyx.ui.dimLabel
import ai.deepmost.corridyx.ui.theme.Accent
import ai.deepmost.corridyx.ui.theme.Bg
import ai.deepmost.corridyx.ui.theme.Good
import ai.deepmost.corridyx.ui.theme.TextDim
import ai.deepmost.corridyx.ui.theme.TextHi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FleetNodeScreen(vm: CorridyxViewModel) {
    val settings by vm.settings.collectAsState()
    val packets by vm.packets.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val done by vm.doneCount.collectAsState()
    var statuses by remember { mutableStateOf<List<EngineStatus>>(emptyList()) }
    LaunchedEffect(Unit) { statuses = vm.registryStatuses() }

    val lifetimeKm = sessions.sumOf { it.distanceM.toDouble() } / 1000.0

    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(10.dp)) {
        Text("FLEET NODE", color = Accent, style = MaterialTheme.typography.titleMedium)

        Panel(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column {
                Text("IDENTITY", color = TextDim, style = MaterialTheme.typography.labelSmall)
                LabeledField("node id", settings.nodeId, "node-…") { v -> vm.edit { nodeId(v) } }
                LabeledField("vehicle label", settings.vehicleLabel, "e.g. KA-01-AV-07") { v -> vm.edit { vehicleLabel(v) } }
            }
        }

        Panel(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column {
                Text("FLEET ENDPOINT", color = TextDim, style = MaterialTheme.typography.labelSmall)
                LabeledField("upload url", settings.uploadEndpoint, "https://fleet…/ingest") { v -> vm.edit { uploadEndpoint(v) } }
                LabeledField("bearer token", settings.uploadToken, "token") { v -> vm.edit { uploadToken(v) } }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text("wifi-only upload", color = TextHi, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = settings.uploadWifiOnly, onCheckedChange = { c -> vm.edit { uploadWifiOnly(c) } })
                }
                if (settings.uploadEndpoint.isBlank())
                    Text("no endpoint set — packets stay queued locally (airplane-mode safe)", color = TextDim, style = MaterialTheme.typography.labelSmall)
            }
        }

        Panel(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column {
                Text("LIFETIME TOTALS", color = TextDim, style = MaterialTheme.typography.labelSmall)
                StatRow("sessions", sessions.size.toString())
                StatRow("km scored", "%.2f".format(lifetimeKm))
                StatRow("packets stored", packets.size.toString())
                StatRow("packets uploaded", done.toString())
            }
        }

        Panel(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column {
                Text("MODEL REGISTRY — engine per head", color = TextDim, style = MaterialTheme.typography.labelSmall)
                Hairline()
                statuses.forEach { st ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(dimLabel(st.head), color = TextHi, modifier = Modifier.weight(1.3f), style = MaterialTheme.typography.labelSmall)
                        Text(st.engineTag, color = if (st.loaded) Good else TextDim, modifier = Modifier.weight(1.4f), style = MaterialTheme.typography.labelSmall)
                        Text(accel(st.accelerator), color = TextDim, modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (statuses.isEmpty()) Text("probing…", color = TextDim, style = MaterialTheme.typography.labelSmall)
                Text(
                    "Drop a fine-tuned .tflite into assets + add a model_registry.json entry to upgrade a head; classical is the day-one default.",
                    color = TextDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun accel(a: Accelerator) = when (a) {
    Accelerator.GPU -> "GPU"; Accelerator.NNAPI -> "NNAPI"; Accelerator.CPU -> "CPU"; Accelerator.NONE -> "—"
}
