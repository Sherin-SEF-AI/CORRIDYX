package ai.deepmost.corridyx.ui.screens

import ai.deepmost.corridyx.ui.CorridyxViewModel
import ai.deepmost.corridyx.ui.Hairline
import ai.deepmost.corridyx.ui.Panel
import ai.deepmost.corridyx.ui.StatRow
import ai.deepmost.corridyx.ui.theme.Accent
import ai.deepmost.corridyx.ui.theme.Bg
import ai.deepmost.corridyx.ui.theme.TextDim
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

@Composable
fun SessionsScreen(vm: CorridyxViewModel) {
    val sessions by vm.sessions.collectAsState()
    val pending by vm.pendingCount.collectAsState()
    val done by vm.doneCount.collectAsState()
    val failed by vm.failedCount.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(color = Bg).padding(10.dp)) {
        Text("SESSIONS", color = Accent, style = MaterialTheme.typography.titleMedium)
        Panel(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column {
                Text("PACKET QUEUE", color = TextDim, style = MaterialTheme.typography.labelSmall)
                StatRow("pending", pending.toString(), pending > 0)
                StatRow("uploaded", done.toString())
                StatRow("failed", failed.toString(), failed > 0)
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(sessions) { s ->
                Panel(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column {
                        Text(
                            "${fmt.format(Date(s.startTs))}  ·  ${s.vehicleLabel.ifBlank { s.nodeId }}",
                            color = Accent, style = MaterialTheme.typography.bodyMedium,
                        )
                        Hairline()
                        StatRow("km scored", "%.2f".format(s.distanceM / 1000f))
                        StatRow("segments", s.segmentsVisited.toString())
                        StatRow("alerts raised", s.alertsRaised.toString())
                        StatRow("ended", s.endTs?.let { fmt.format(Date(it)) } ?: "active")
                        TextButton(onClick = {
                            scope.launch {
                                val pkts = vm.packetsForSession(s.sessionId)
                                if (pkts.isNotEmpty()) SessionExport.export(context, s.sessionId, pkts)
                            }
                        }) { Text("EXPORT PACKETS (zip)", color = Accent) }
                    }
                }
            }
            if (sessions.isEmpty()) item { Text("no sessions yet", color = TextDim, modifier = Modifier.padding(8.dp)) }
        }
    }
}
