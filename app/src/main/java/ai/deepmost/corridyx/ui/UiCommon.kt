package ai.deepmost.corridyx.ui

import ai.deepmost.corridyx.dimensions.Dim
import ai.deepmost.corridyx.ui.theme.Accent
import ai.deepmost.corridyx.ui.theme.Hairline
import ai.deepmost.corridyx.ui.theme.Surface
import ai.deepmost.corridyx.ui.theme.TextDim
import ai.deepmost.corridyx.ui.theme.TextHi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Stable display order of all dimensions. */
val DIM_ORDER = listOf(
    Dim.LANE_VIS, Dim.GNSS_Q, Dim.ILLUM, Dim.CHAOS, Dim.ROUGH,
    Dim.LENS, Dim.GLARE, Dim.ATMO, Dim.NIGHTQ,
)

fun dimLabel(id: String): String = when (id) {
    Dim.LANE_VIS -> "LANE MARKINGS"
    Dim.GNSS_Q -> "GNSS QUALITY"
    Dim.ILLUM -> "ILLUMINATION"
    Dim.CHAOS -> "AGENT CHAOS"
    Dim.ROUGH -> "ROAD ROUGHNESS"
    Dim.LENS -> "LENS DIRT"
    Dim.GLARE -> "GLARE / SUN"
    Dim.ATMO -> "RAIN / FOG"
    Dim.NIGHTQ -> "NIGHT QUALITY"
    else -> id.uppercase()
}

fun dimShort(id: String): String = when (id) {
    Dim.LANE_VIS -> "LANE"; Dim.GNSS_Q -> "GNSS"; Dim.ILLUM -> "ILUM"; Dim.CHAOS -> "CHAOS"
    Dim.ROUGH -> "ROUGH"; Dim.LENS -> "LENS"; Dim.GLARE -> "GLARE"; Dim.ATMO -> "ATMO"; Dim.NIGHTQ -> "NIGHT"
    else -> id.take(5).uppercase()
}

/** Monochrome luminance ramp for scores (dim = poor, bright = good). Accent stays reserved. */
fun scoreLuma(score: Float): Color {
    val s = score.coerceIn(0f, 1f)
    val v = (0.22f + 0.62f * s)
    return Color(v, v, v, 1f)
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Surface)
            .padding(10.dp)
    ) { content() }
}

@Composable
fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
}

@Composable
fun StatRow(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextDim, fontWeight = FontWeight.Normal, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        Text(value, color = if (highlight) Accent else TextHi, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    }
}

/** Horizontal mini-bar; [worst] highlights the worst-scoring dimension in the single accent. */
@Composable
fun MiniBar(label: String, score: Float, worst: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = if (worst) Accent else TextDim, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            Text("%.2f".format(score), color = if (worst) Accent else TextHi, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(Hairline)) {
            Box(
                Modifier
                    .fillMaxWidth(score.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(if (worst) Accent else scoreLuma(score))
            )
        }
    }
}
