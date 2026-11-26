package com.ridedeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow

/** Palette that changes between day and night. Fixed accent colors live in [DeckColors]. */
@Immutable
class DeckPalette(
    val isDay: Boolean,
    val wallpaper: Brush,
    val card: Color,
    val cardHigh: Color,
    val glassTop: Color,
    val glassBottom: Color,
    val border: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val muted: Color,
    /** Faint fill for tracks and secondary buttons. */
    val track: Color,
    /** Background and foreground for the high-emphasis (play) button. */
    val strong: Color,
    val onStrong: Color,
    /** Bottom of the media card gradient. */
    val mediaBase: Color,
    val scrim: Color,
)

private val NightPalette = DeckPalette(
    isDay = false,
    wallpaper = Brush.radialGradient(
        colors = listOf(Color(0xFF14284D), Color(0xFF0A0F1E), Color(0xFF000000)),
        center = Offset(300f, 120f),
        radius = 1600f,
    ),
    card = Color(0xFF1C1C1E),
    cardHigh = Color(0xFF2C2C2E),
    glassTop = Color(0x2EFFFFFF),
    glassBottom = Color(0x14FFFFFF),
    border = Color(0x26FFFFFF),
    text = Color.White,
    textSecondary = Color(0xB3FFFFFF),
    textTertiary = Color(0x99FFFFFF),
    muted = Color(0xFF8E8E93),
    track = Color(0x33FFFFFF),
    strong = Color.White,
    onStrong = Color.Black,
    mediaBase = Color(0xFF0E0E10),
    scrim = Color(0xFF000000),
)

private val DayPalette = DeckPalette(
    isDay = true,
    wallpaper = Brush.radialGradient(
        colors = listOf(Color(0xFFF4F8FF), Color(0xFFDCE6F5), Color(0xFFC3D2E8)),
        center = Offset(300f, 120f),
        radius = 1600f,
    ),
    card = Color(0xFFFFFFFF),
    cardHigh = Color(0xFFE3E7EE),
    glassTop = Color(0xE6FFFFFF),
    glassBottom = Color(0xB3FFFFFF),
    border = Color(0x1F000000),
    text = Color(0xFF0B0E14),
    textSecondary = Color(0xFF3B4450),
    textTertiary = Color(0xFF55606C),
    muted = Color(0xFF55606C),
    track = Color(0x1F000000),
    strong = Color(0xFF0B0E14),
    onStrong = Color.White,
    mediaBase = Color(0xFFFFFFFF),
    scrim = Color(0xFFF4F8FF),
)

val LocalDeckPalette = staticCompositionLocalOf { NightPalette }

object DeckColors {
    val Background = Color(0xFF000000)
    val Blue = Color(0xFF0A84FF)
    val Cyan = Color(0xFF64D2FF)
    val Green = Color(0xFF30D158)
    val Orange = Color(0xFFFF9F0A)
    val Red = Color(0xFFFF453A)

    /** Secondary text on the always-dark map panels. For themed surfaces use [DeckPalette.muted]. */
    val Gray = Color(0xFF8E8E93)

    /** Raised fill inside the always-dark map panels (search field, secondary buttons). */
    val PanelHigh = Color(0xFF2C2C2E)

    val Card: Color @Composable get() = LocalDeckPalette.current.card
    val CardHigh: Color @Composable get() = LocalDeckPalette.current.cardHigh
}

/**
 * WCAG 2.x relative luminance of an opaque colour. Colours with alpha are treated as if they were
 * opaque; every surface that uses this helper is a painted fill, not a translucent overlay.
 */
fun relativeLuminance(color: Color): Float {
    fun channel(value: Float): Float =
        if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
}

/** WCAG contrast ratio between two opaque colours; 1 is equal, 21 is black on white. */
fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * The readable text/icon colour for a filled button or chip. Black on the light accents (green,
 * orange, blue, red in the day palette) and white on the dark fills, chosen by measured luminance
 * rather than by hue. Explicit `textColor` overrides still win at every call site.
 */
fun readableOn(background: Color): Color =
    if (contrastRatio(background, Color.Black) >= contrastRatio(background, Color.White)) Color.Black else Color.White

/**
 * Warning text on a themed card. [DeckColors.Orange] is tuned for the dark map panels; on the light
 * day cards it drops below WCAG AA, so the day variant uses a darker burnt amber.
 */
fun warningColor(palette: DeckPalette): Color =
    if (palette.isDay) Color(0xFF8A4B00) else DeckColors.Orange

/** Positive/confirmation text on a themed card; the bright green only works on dark surfaces. */
fun successColor(palette: DeckPalette): Color =
    if (palette.isDay) Color(0xFF0B6B2E) else DeckColors.Green

/** Destructive text on a themed card; the night red is too light on white. */
fun dangerColor(palette: DeckPalette): Color =
    if (palette.isDay) Color(0xFFB3261E) else DeckColors.Red

/** The active palette without going through composition, for pure contrast checks and previews. */
internal fun deckPalette(isDay: Boolean): DeckPalette = if (isDay) DayPalette else NightPalette

/** Shared measurements for settings cards, so the six pages keep one card rhythm. */
object SettingsCardDefaults {
    val Radius = 20.dp
    val Padding = 16.dp
    val TitleSize = 19.sp
    val DescriptionSize = 14.sp
}

/** The settings card surface; its color still comes from the active [DeckPalette]. */
fun Modifier.settingsCard(): Modifier = composed {
    clip(RoundedCornerShape(SettingsCardDefaults.Radius)).background(LocalDeckPalette.current.card)
}

@Composable
fun DeckTheme(isDay: Boolean = false, content: @Composable () -> Unit) {
    val palette = deckPalette(isDay)
    CompositionLocalProvider(LocalDeckPalette provides palette) {
        MaterialTheme(
            colorScheme = if (isDay) {
                lightColorScheme(background = palette.card, surface = palette.card, primary = DeckColors.Blue)
            } else {
                darkColorScheme(background = DeckColors.Background, surface = palette.card, primary = DeckColors.Blue)
            },
            content = content,
        )
    }
}

/** An upright phone: too narrow for the side-by-side layouts and for the large type of a wide screen. */
@Composable
fun isNarrowScreen(): Boolean = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 560

/**
 * A text size that follows the system font size only up to [maxScale]: a gauge or the dock has a fixed amount
 * of room, and a 150% font would push the text out of it (or into the arc around it).
 */
@Composable
fun cappedSp(base: Float, maxScale: Float = 1.15f): TextUnit {
    val fontScale = LocalDensity.current.fontScale
    return (base * (maxScale / fontScale).coerceAtMost(1f)).sp
}

fun Modifier.card(shape: Int = 28): Modifier = composed {
    val palette = LocalDeckPalette.current
    val rounded = RoundedCornerShape(shape.dp)
    clip(rounded)
        .background(Brush.verticalGradient(listOf(palette.glassTop, palette.glassBottom)))
        .border(1.dp, palette.border, rounded)
}
