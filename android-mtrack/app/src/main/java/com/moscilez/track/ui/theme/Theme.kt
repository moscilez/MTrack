package com.moscilez.track.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moscilez.track.R

/** Extra tokens outside Material's scheme: hairlines, secondary ink, accent tint, map ground. */
@Immutable
data class MTColors(
    val bg: Color,
    val surface: Color,
    val ink: Color,
    val ink2: Color,
    val line: Color,
    val accent: Color,
    val accentSoft: Color,
    val chip: Color,
    val mapGround: Color,
    val mapGrid: Color,
    val danger: Color,
    val dark: Boolean,
)

val LightMT = MTColors(
    bg = Color(0xFFF6F4EE), surface = Color(0xFFFFFFFF), ink = Color(0xFF111111), ink2 = Color(0xFF6B6B66),
    line = Color(0xFFDAD7CE), accent = Color(0xFF1F3BFF), accentSoft = Color(0xFFE6E9FF), chip = Color(0xFFECE9E1),
    mapGround = Color(0xFFEDEAE2), mapGrid = Color(0xFFDCD8CD), danger = Color(0xFFC4321C), dark = false,
)

val DarkMT = MTColors(
    bg = Color(0xFF0F0F0E), surface = Color(0xFF181817), ink = Color(0xFFF2F0EA), ink2 = Color(0xFF9C9A93),
    line = Color(0xFF2E2D2A), accent = Color(0xFF6176FF), accentSoft = Color(0xFF1C2250), chip = Color(0xFF242422),
    mapGround = Color(0xFF1A1A18), mapGrid = Color(0xFF2A2926), danger = Color(0xFFFF6A52), dark = true,
)

val LocalMT = staticCompositionLocalOf { LightMT }

@OptIn(ExperimentalTextApi::class)
private fun plexSans(weight: Int) = Font(
    R.font.ibmplexsans_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val PlexSans = FontFamily(plexSans(400), plexSans(500), plexSans(600), plexSans(700))

val PlexMono = FontFamily(
    Font(R.font.ibmplexmono_regular, FontWeight.Normal),
    Font(R.font.ibmplexmono_medium, FontWeight.Medium),
    Font(R.font.ibmplexmono_semibold, FontWeight.SemiBold),
    Font(R.font.ibmplexmono_bold, FontWeight.Bold),
)

/** Numeric styles: monospaced figures never jitter while a value ticks. */
object Num {
    val hero = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 72.sp, lineHeight = 76.sp, letterSpacing = (-2).sp)
    val xl = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 52.sp, lineHeight = 56.sp, letterSpacing = (-1.5).sp)
    val l = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-1).sp)
    val m = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp)
    val s = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp)
    val label = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.8.sp)
}

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight(700), fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight(700), fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight(600), fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight(600), fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight(600), fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight(600), fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight(400), fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight(400), fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight(400), fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight(500), fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight(500), fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight(500), fontSize = 12.sp, lineHeight = 16.sp),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(4.dp),
)

@Composable
fun MTrackTheme(theme: String = "SYSTEM", content: @Composable () -> Unit) {
    val dark = when (theme) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }
    val mt = if (dark) DarkMT else LightMT
    val scheme = if (dark) darkColorScheme(
        primary = mt.accent, onPrimary = Color.White, background = mt.bg, onBackground = mt.ink,
        surface = mt.bg, onSurface = mt.ink, surfaceVariant = mt.chip, onSurfaceVariant = mt.ink2,
        surfaceContainer = mt.surface, surfaceContainerHigh = mt.surface, surfaceContainerHighest = mt.chip,
        surfaceContainerLow = mt.surface, outline = mt.line, outlineVariant = mt.line, error = mt.danger,
        secondaryContainer = mt.accentSoft, onSecondaryContainer = mt.ink, primaryContainer = mt.accentSoft,
    ) else lightColorScheme(
        primary = mt.accent, onPrimary = Color.White, background = mt.bg, onBackground = mt.ink,
        surface = mt.bg, onSurface = mt.ink, surfaceVariant = mt.chip, onSurfaceVariant = mt.ink2,
        surfaceContainer = mt.surface, surfaceContainerHigh = mt.surface, surfaceContainerHighest = mt.chip,
        surfaceContainerLow = mt.surface, outline = mt.line, outlineVariant = mt.line, error = mt.danger,
        secondaryContainer = mt.accentSoft, onSecondaryContainer = mt.ink, primaryContainer = mt.accentSoft,
    )
    CompositionLocalProvider(LocalMT provides mt) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}
