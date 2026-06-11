package ai.deepmost.corridyx.ui.screens

import ai.deepmost.corridyx.capture.OverlayState
import ai.deepmost.corridyx.service.CaptureController
import ai.deepmost.corridyx.ui.CorridyxViewModel
import ai.deepmost.corridyx.ui.DIM_ORDER
import ai.deepmost.corridyx.ui.MiniBar
import ai.deepmost.corridyx.ui.Panel
import ai.deepmost.corridyx.ui.StatRow
import ai.deepmost.corridyx.ui.dimLabel
import ai.deepmost.corridyx.ui.dimShort
import ai.deepmost.corridyx.ui.theme.Accent
import ai.deepmost.corridyx.ui.theme.Bg
import ai.deepmost.corridyx.ui.theme.TextDim
import ai.deepmost.corridyx.ui.theme.Warn
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * DRIVE — landscape split: camera + live overlay on the left, ODD readout / per-dimension bars /
 * perception conditions / START control on the right (always visible, no deep scroll).
 */
@Composable
fun DriveScreen(vm: CorridyxViewModel) {
    val running by CaptureController.running.collectAsState()
    val live by CaptureController.live.collectAsState()
    val overlay by CaptureController.overlay.collectAsState()
    val alert by CaptureController.alert.collectAsState()
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val worst = live.instantSamples.minByOrNull { it.value }?.key

    DisposableEffect(Unit) { onDispose { CaptureController.setSurfaceProvider(null) } }

    Row(Modifier.fillMaxSize().background(Bg).padding(8.dp)) {

        // ---------- LEFT: camera preview + overlay ----------
        Box(Modifier.weight(1.15f).fillMaxHeight().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { pv -> CaptureController.setSurfaceProvider(pv.surfaceProvider) },
            )
            OverlayCanvas(overlay)
            if (!running) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("CAMERA IDLE", color = TextDim, style = MaterialTheme.typography.titleMedium)
                }
            }
            alert?.let { a ->
                Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Warn).padding(8.dp)) {
                    Text(a.message, color = Bg, style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // ---------- RIGHT: readouts + control ----------
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll)) {

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("ODD", color = TextDim, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (running) live.liveOdd.toInt().toString().padStart(2, '0') else "--",
                    color = if (running) Accent else TextDim,
                    style = MaterialTheme.typography.displayLarge,
                )
            }
            StatRow("SEG", live.segmentId?.take(12) ?: "no-fix", live.segmentId != null)
            StatRow("BUCKET", live.timeBucket.name)
            StatRow("SPEED", "%.1f m/s".format(live.speedMps))
            StatRow("DUTY", live.dutyCycle.name, live.dutyCycle.name != "NORMAL")
            StatRow("GPS", if (live.gpsValid) "FIX" else "GAP", !live.gpsValid)
            StatRow("FRAMES", live.frameCount.toString())

            Button(
                onClick = { if (running) CaptureController.stop(context) else CaptureController.start(context) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) Warn else Accent, contentColor = Bg,
                ),
            ) { Text(if (running) "STOP SHIFT CAPTURE" else "START SHIFT CAPTURE") }

            Panel(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column {
                    Text("PERCEPTION CONDITIONS (GLARYX)", color = TextDim, style = MaterialTheme.typography.labelSmall)
                    val c = live.conditions
                    StatRow("trust", "%.2f".format(c.overallTrust), c.overallTrust < 0.5f)
                    StatRow("lens", "%.2f".format(c.lens))
                    StatRow("glare", "%.2f".format(c.glare))
                    StatRow("atmo", "%.2f".format(c.atmo))
                    StatRow("nightq", "%.2f".format(c.nightq))
                }
            }

            Panel(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column {
                    Text("DIMENSION SUBSCORES (segment mean)", color = TextDim, style = MaterialTheme.typography.labelSmall)
                    DIM_ORDER.forEach { d ->
                        val v = live.dimMeans[d]
                        if (v != null) MiniBar(dimShort(d) + "  " + dimLabel(d), v, worst == d)
                    }
                    if (live.dimMeans.isEmpty()) Text("awaiting samples…", color = TextDim, style = MaterialTheme.typography.bodySmall)
                }
            }

            Text(
                "100% on-device. No continuous video is ever stored or sent.",
                color = TextDim, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun OverlayCanvas(overlay: OverlayState) {
    if (overlay.frameW == 0) return
    Canvas(Modifier.fillMaxSize()) {
        val sx = size.width / overlay.frameW
        val sy = size.height / overlay.frameH
        if (overlay.lensGridCols > 0) {
            val bw = overlay.frameW.toFloat() / overlay.lensGridCols
            val bh = overlay.frameH.toFloat() / overlay.lensGridRows
            for (ry in 0 until overlay.lensGridRows) for (rx in 0 until overlay.lensGridCols) {
                if (overlay.lensDirtMask.getOrNull(ry * overlay.lensGridCols + rx) == true) {
                    drawRect(
                        color = Color(0xFFE8FF3A),
                        topLeft = Offset(rx * bw * sx, ry * bh * sy),
                        size = Size(bw * sx, bh * sy),
                        style = Stroke(width = 2f),
                    )
                }
            }
        }
        for (b in overlay.glareBlobs) {
            drawRect(
                color = Color(0xFFFF5A3A),
                topLeft = Offset(b.x0 * sx, b.y0 * sy),
                size = Size((b.x1 - b.x0) * sx, (b.y1 - b.y0) * sy),
                style = Stroke(width = 2f),
            )
        }
    }
}
