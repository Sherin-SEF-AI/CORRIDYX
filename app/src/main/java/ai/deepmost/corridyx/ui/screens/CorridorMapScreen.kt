package ai.deepmost.corridyx.ui.screens

import ai.deepmost.corridyx.dimensions.Dim
import ai.deepmost.corridyx.packet.SegmentScorePacket
import ai.deepmost.corridyx.ui.CorridyxViewModel
import ai.deepmost.corridyx.ui.DIM_ORDER
import ai.deepmost.corridyx.ui.Hairline
import ai.deepmost.corridyx.ui.Panel
import ai.deepmost.corridyx.ui.StatRow
import ai.deepmost.corridyx.ui.dimShort
import ai.deepmost.corridyx.ui.scoreLuma
import ai.deepmost.corridyx.ui.theme.Accent
import ai.deepmost.corridyx.ui.theme.Bg
import ai.deepmost.corridyx.ui.theme.TextDim
import ai.deepmost.corridyx.ui.theme.TextHi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlin.math.cos

private val json = Json { ignoreUnknownKeys = true }

private class SegAgg(val key: String, val lat: Double, val lon: Double) {
    // per timeBucket: oddSum/n and per-dim sum/n
    val odd = HashMap<String, MutableList<Float>>()
    val dims = HashMap<String, HashMap<String, MutableList<Float>>>() // bucket -> dim -> values
    fun add(bucket: String, oddScore: Float, dimScores: Map<String, Float>) {
        odd.getOrPut(bucket) { mutableListOf() }.add(oddScore)
        val db = dims.getOrPut(bucket) { HashMap() }
        for ((d, v) in dimScores) db.getOrPut(d) { mutableListOf() }.add(v)
    }
    fun oddMeanAll(): Float {
        val all = odd.values.flatten(); return if (all.isEmpty()) 0f else all.average().toFloat()
    }
    fun dimMean(bucket: String, dim: String): Float? =
        dims[bucket]?.get(dim)?.let { if (it.isEmpty()) null else it.average().toFloat() }
    fun dimMeanAll(dim: String): Float? {
        val all = dims.values.mapNotNull { it[dim] }.flatten()
        return if (all.isEmpty()) null else all.average().toFloat()
    }
}

@Composable
fun CorridorMapScreen(vm: CorridyxViewModel) {
    val packets by vm.packets.collectAsState()
    var filterDim by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    // decode manifests -> aggregated segments
    val segs = remember(packets) {
        val map = HashMap<String, SegAgg>()
        for (p in packets) {
            val pk = runCatching { json.decodeFromString(SegmentScorePacket.serializer(), p.manifestJson) }.getOrNull() ?: continue
            val key = "${pk.segment.geohash7}:${pk.segment.headingBucket}"
            val agg = map.getOrPut(key) { SegAgg(key, pk.segment.centroidLat, pk.segment.centroidLon) }
            agg.add(pk.timeBucket, pk.oddScore / 100f, pk.scores.mapValues { it.value.mean })
        }
        map.values.toList()
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(10.dp)) {
        Text("CORRIDOR ODD MAP — this node", color = Accent, style = MaterialTheme.typography.titleMedium)
        Text("${segs.size} segments • filter colours by dimension", color = TextDim, style = MaterialTheme.typography.labelSmall)

        // dimension filter chips
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            FilterChip("ODD", filterDim == null) { filterDim = null }
            DIM_ORDER.forEach { d -> FilterChip(dimShort(d), filterDim == d) { filterDim = d } }
        }

        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0E0F11))
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        scale = (scale * zoomChange).coerceIn(0.2f, 12f)
                        pan += panChange
                    }
                }
                .pointerInput(segs, scale, pan) {
                    detectTapGestures { tap ->
                        // hit test using same projection
                        val proj = projector(segs, size.width.toFloat(), size.height.toFloat(), scale, pan)
                        var best: String? = null; var bestD = 36f
                        for (s in segs) {
                            val pt = proj(s.lat, s.lon)
                            val d = (pt - tap).getDistance()
                            if (d < bestD) { bestD = d; best = s.key }
                        }
                        selected = best
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (segs.isEmpty()) return@Canvas
                val proj = projector(segs, size.width, size.height, scale, pan)
                for (s in segs) {
                    val pt = proj(s.lat, s.lon)
                    val v = (if (filterDim == null) s.oddMeanAll() else s.dimMeanAll(filterDim!!)) ?: continue
                    val isSel = s.key == selected
                    drawCircle(
                        color = if (isSel) Accent else scoreLuma(v),
                        radius = if (isSel) 9f else 6f,
                        center = pt,
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("pinch=zoom  drag=pan  tap=inspect", color = TextDim, style = MaterialTheme.typography.labelSmall)
            Text("zoom ${"%.1f".format(scale)}x", color = TextDim, style = MaterialTheme.typography.labelSmall)
        }

        // breakdown sheet for the selected segment (day vs night side by side)
        selected?.let { key ->
            val s = segs.firstOrNull { it.key == key }
            if (s != null) {
                Panel(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column {
                        Text("SEGMENT $key", color = Accent, style = MaterialTheme.typography.bodyMedium)
                        StatRow("centroid", "%.5f, %.5f".format(s.lat, s.lon))
                        Hairline()
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text("DIM", color = TextDim, modifier = Modifier.weight(1.4f), style = MaterialTheme.typography.labelSmall)
                            Text("DAY", color = TextDim, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            Text("DUSK", color = TextDim, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            Text("NIGHT", color = TextDim, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                        }
                        DIM_ORDER.forEach { d ->
                            if (s.dimMeanAll(d) == null) return@forEach
                            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                Text(dimShort(d), color = TextHi, modifier = Modifier.weight(1.4f), style = MaterialTheme.typography.labelSmall)
                                Cell(s.dimMean("DAY", d), Modifier.weight(1f))
                                Cell(s.dimMean("DUSK_DAWN", d), Modifier.weight(1f))
                                Cell(s.dimMean("NIGHT", d), Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(v: Float?, modifier: Modifier) {
    Text(
        v?.let { "%.2f".format(it) } ?: "—",
        color = v?.let { scoreLuma(it) } ?: TextDim,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 6.dp).background(if (selected) Accent else Color(0xFF17181B))
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, color = if (selected) Bg else TextDim, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
    }
}

/** Equirectangular projection around the segment-set mean (city-scale accurate), with zoom + pan. */
private fun projector(segs: List<SegAgg>, w: Float, h: Float, scale: Float, pan: Offset): (Double, Double) -> Offset {
    if (segs.isEmpty()) return { _, _ -> Offset(w / 2, h / 2) }
    val meanLat = segs.map { it.lat }.average()
    val meanLon = segs.map { it.lon }.average()
    val mPerLat = 111_320.0
    val mPerLon = 111_320.0 * cos(Math.toRadians(meanLat))
    val xs = segs.map { (it.lon - meanLon) * mPerLon }
    val ys = segs.map { -(it.lat - meanLat) * mPerLat }
    val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
    val spanX = (maxX - minX).coerceAtLeast(50.0)
    val spanY = (maxY - minY).coerceAtLeast(50.0)
    val fit = (minOf(w / spanX, h / spanY) * 0.85).toFloat()
    return { lat, lon ->
        val mx = (lon - meanLon) * mPerLon
        val my = -(lat - meanLat) * mPerLat
        val px = w / 2 + ((mx - (minX + maxX) / 2) * fit * scale).toFloat() + pan.x
        val py = h / 2 + ((my - (minY + maxY) / 2) * fit * scale).toFloat() + pan.y
        Offset(px, py)
    }
}
