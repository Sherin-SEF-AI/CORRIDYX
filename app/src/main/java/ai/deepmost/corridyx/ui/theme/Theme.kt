package ai.deepmost.corridyx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

// "Operational Materialism": matte near-black, one earned accent, monospace numerics, hairlines.
val Bg = Color(0xFF0A0B0C)
val Surface = Color(0xFF121315)
val SurfaceHi = Color(0xFF17181B)
val Hairline = Color(0xFF26282C)
val TextHi = Color(0xFFE6E8EA)
val TextDim = Color(0xFF8A8F95)
val Accent = Color(0xFFE8FF3A)      // the single earned accent
val Warn = Color(0xFFFF5A3A)
val Good = Color(0xFF49E08B)

private val CorridyxColors = darkColorScheme(
    primary = Accent,
    onPrimary = Bg,
    background = Bg,
    onBackground = TextHi,
    surface = Surface,
    onSurface = TextHi,
    surfaceVariant = SurfaceHi,
    onSurfaceVariant = TextDim,
    error = Warn,
    outline = Hairline,
)

private val Mono = FontFamily.Monospace
private val CorridyxType = Typography(
    displayLarge = TextStyle(fontFamily = Mono, fontSize = 56.sp),
    headlineSmall = TextStyle(fontFamily = Mono, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Mono, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = Mono, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = Mono, fontSize = 11.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 10.sp),
)

@Composable
fun CorridyxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CorridyxColors, typography = CorridyxType, content = content)
}
