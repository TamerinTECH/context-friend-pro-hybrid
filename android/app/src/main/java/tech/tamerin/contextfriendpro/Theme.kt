package tech.tamerin.contextfriendpro

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** "Quiet Blue" design tokens (docs/design/README.md). One scheme for every component: no default lavender surfaces. */
object Brand {
    val Background = Color(0xFFF7F8FA)
    val Surface = Color.White
    val SurfaceContainer = Color(0xFFEEF2F7)
    val Navy = Color(0xFF162E51)
    val Muted = Color(0xFF5F6E83)
    val Primary = Color(0xFF245FD6)
    val PrimaryContainer = Color(0xFFEAF1FF)
    val OnPrimaryContainer = Color(0xFF163B85)
    val Accent = Color(0xFFF6C453)
    val Success = Color(0xFF177353)
    val SuccessContainer = Color(0xFFE8F4EF)
    val Warning = Color(0xFF79520C)
    val WarningContainer = Color(0xFFFFF3D6)
    val Error = Color(0xFFA72C36)
    val ErrorContainer = Color(0xFFFDECEE)
    val Outline = Color(0xFF718098)
    val Divider = Color(0xFFDCE4F0)
    val RecordingSecondary = Color(0xFFCDDAEF)
    val WaveBlue = Color(0xFF71A3FF)
    val RecordRed = Color(0xFFFF7979)
}

private val QuietBlue = lightColorScheme(
    primary = Brand.Primary, onPrimary = Color.White,
    primaryContainer = Brand.PrimaryContainer, onPrimaryContainer = Brand.OnPrimaryContainer,
    inversePrimary = Color(0xFF9AB8FF),
    secondary = Brand.Navy, onSecondary = Color.White,
    secondaryContainer = Brand.PrimaryContainer, onSecondaryContainer = Brand.OnPrimaryContainer,
    tertiary = Brand.Accent, onTertiary = Brand.Navy,
    tertiaryContainer = Brand.WarningContainer, onTertiaryContainer = Brand.Warning,
    background = Brand.Background, onBackground = Brand.Navy,
    surface = Brand.Surface, onSurface = Brand.Navy,
    surfaceVariant = Brand.SurfaceContainer, onSurfaceVariant = Brand.Muted,
    surfaceTint = Color.Transparent,
    inverseSurface = Brand.Navy, inverseOnSurface = Color.White,
    error = Brand.Error, onError = Color.White, errorContainer = Brand.ErrorContainer, onErrorContainer = Brand.Error,
    outline = Brand.Outline, outlineVariant = Brand.Divider, scrim = Color.Black,
    surfaceBright = Color.White, surfaceDim = Color(0xFFE3E8EF),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Brand.Background,
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerHighest = Brand.SurfaceContainer,
)

private val QuietType = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val QuietShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable fun ContextFriendTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = QuietBlue, typography = QuietType, shapes = QuietShapes, content = content)

/** Status and navigation bar icons: dark on light screens, light on the navy recording screen. */
@Composable fun SystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = dark
            isAppearanceLightNavigationBars = dark
        }
    }
}

/** The ContextFriend logo (docs/brand). Decorative next to the product name, so screen readers skip it. */
@Composable fun BrandMark(size: Dp = 32.dp) {
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(size))
}

/** Vertical level bars from measured microphone peaks, newest on the end side. Never animated without signal. */
@Composable fun LevelBars(levels: List<Float>, modifier: Modifier = Modifier, bars: Int = 21) {
    Canvas(modifier) {
        val slot = size.width / bars
        val barWidth = slot * 0.55f
        val values = levels.takeLast(bars)
        val start = bars - values.size
        for (i in 0 until bars) {
            val level = values.getOrNull(i - start) ?: 0f
            val scaled = (kotlin.math.sqrt(level.coerceIn(0f, 1f)) * 1.6f).coerceIn(0.06f, 1f)
            val h = size.height * scaled
            val color = if (i % 3 == 1) Brand.Accent else Brand.WaveBlue
            drawRoundRect(color, topLeft = Offset(i * slot + (slot - barWidth) / 2, (size.height - h) / 2),
                size = Size(barWidth, h), cornerRadius = CornerRadius(barWidth / 2, barWidth / 2))
        }
    }
}

/** A few Material symbols that material-icons-core does not ship. Paths from Material Icons (Apache-2.0). */
object Glyphs {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()

    val Mic = icon("mic", "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
    val MicOff = icon("mic_off", "M19,11h-1.7c0,0.74 -0.16,1.43 -0.43,2.05l1.23,1.23c0.56,-0.98 0.9,-2.09 0.9,-3.28zM14.98,11.17c0,-0.06 0.02,-0.11 0.02,-0.17L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v0.18l5.98,5.99zM4.27,3L3,4.27l6.01,6.01L9.01,11c0,1.66 1.33,3 2.99,3 0.22,0 0.44,-0.03 0.65,-0.08l1.66,1.66c-0.71,0.33 -1.5,0.52 -2.31,0.52 -2.76,0 -5.3,-2.1 -5.3,-5.1L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c0.91,-0.13 1.77,-0.45 2.54,-0.9L19.73,21 21,19.73 4.27,3z")
    val Pause = icon("pause", "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z")
    val Stop = icon("stop", "M6,6h12v12H6z")
    val Download = icon("download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
    val Cloud = icon("cloud", "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z")
    val Qr = icon("qr", "M3,11h8V3H3V11zM5,5h4v4H5V5zM13,3v8h8V3H13zM19,9h-4V5h4V9zM3,21h8v-8H3V21zM5,15h4v4H5V15zM18,13h-2v3h-3v2h3v3h2v-3h3v-2h-3z")
    val Link = icon("link", "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4L11,7L7,7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9L7,15.1c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2L8,11v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4L13,17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z")
    val Phone = icon("phone", "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2L19,3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19L7,19L7,5h10v14z")
    val Shield = icon("shield", "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12L21,5l-9,-4z")
    val Chip = icon("chip", "M15,9H9v6h6V9zM13,13h-2v-2h2V13zM21,11V9h-2V7c0,-1.1 -0.9,-2 -2,-2h-2V3h-2v2h-2V3H9v2H7C5.9,5 5,5.9 5,7v2H3v2h2v2H3v2h2v2c0,1.1 0.9,2 2,2h2v2h2v-2h2v2h2v-2h2c1.1,0 2,-0.9 2,-2v-2h2v-2h-2v-2H21zM17,17H7V7h10V17z")
    val History = icon("history", "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z")
    val Storage = icon("storage", "M2,20h20v-4L2,16v4zM4,17h2v2L4,19v-2zM2,4v4h20L22,4L2,4zM6,7L4,7L4,5h2v2zM2,14h20v-4L2,10v4zM4,11h2v2L4,13v-2z")
    val Translate = icon("translate", "M12.87,15.07l-2.54,-2.51 0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53L17,6L17,4h-7L10,2L8,2v2L1,4v1.99h11.17C11.5,7.92 10.44,9.75 9,11.35 8.07,10.32 7.3,9.19 6.69,8h-2c0.73,1.63 1.73,3.17 2.98,4.56l-5.09,5.02L4,19l5,-5 3.11,3.11 0.76,-2.04zM18.5,10h-2L12,22h2l1.12,-3h4.75L21,22h2l-4.5,-12zM15.88,17l1.62,-4.33L19.12,17h-3.24z")
    val Tune = icon("tune", "M3,17v2h6v-2L3,17zM3,5v2h10L13,5L3,5zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2L3,11v2h4v2h2L9,9L7,9zM21,13v-2L11,11v2h10zM15,9h2L17,7h4L21,5h-4L17,3h-2v6z")
    val Waves = icon("waves", "M7,18h2L9,6L7,6v12zM11,22h2L13,2h-2v20zM3,14h2v-4L3,10v4zM15,18h2L17,6h-2v12zM19,10v4h2v-4h-2z")
}
