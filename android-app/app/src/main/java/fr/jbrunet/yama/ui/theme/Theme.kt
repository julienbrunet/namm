package fr.jbrunet.yama.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Coloured glow drawn on the background (position and radius as a fraction of the screen). */
@Immutable
data class Glow(val x: Float, val y: Float, val radius: Float, val color: Color)

/**
 * Palette of a theme: background and tiles, contrasted text, rain blue.
 * Optional fields are used by the colourful themes (gradients, glows, tinted tiles).
 */
@Immutable
data class SlatePalette(
    val background: Color,
    val tile: Color,
    val tileHighlight: Color,
    val primary: Color,
    val secondary: Color,
    val muted: Color,
    val rain: Color,
    /** Light status bar icons, dark Material scheme. */
    val isDark: Boolean,
    val backgroundBrush: Brush = SolidColor(background),
    val glows: List<Glow> = emptyList(),
    /** Gradient card behind the current weather (null = no card). */
    val heroBrush: Brush? = null,
    /** Colour of each “Now” tile (empty = [tile]). */
    val tileTints: List<Color> = emptyList(),
    /** Gradient of the temperature curve, from left to right. */
    val curveColors: List<Color> = listOf(primary, secondary),
    /** Main button of the bottom bar. */
    val buttonBrush: Brush = SolidColor(primary),
    val onButton: Color = background,
    /** Darkened map background. */
    val darkMap: Boolean = isDark,
    /** Home screen over a landscape drawn from the weather and the time of day (Nature theme). */
    val scenic: Boolean = false,
)

/** Dark “slate” theme (original design). */
val DarkSlate = SlatePalette(
    background = Color(0xFF353A45),
    tile = Color(0xFF2F333D),
    tileHighlight = Color(0xFF3D424E),
    primary = Color(0xFFF2F3F5),
    secondary = Color(0xFF9AA0AC),
    muted = Color(0xFF6B717D),
    rain = Color(0xFF8FB3D9),
    isDark = true,
)

/** Light theme: same restraint, pearl grey and slate text. */
val LightSlate = SlatePalette(
    background = Color(0xFFE9EBEF),
    tile = Color(0xFFF8F9FA),
    tileHighlight = Color(0xFFDADDE3),
    primary = Color(0xFF555B67),
    secondary = Color(0xFF7A808B),
    muted = Color(0xFFA0A5AE),
    rain = Color(0xFF3F7CC4),
    isDark = false,
)

/** Sky: bright blue gradient, frosted glass tiles, white text. */
val SkySlate = SlatePalette(
    background = Color(0xFF2F78C8),
    tile = Color(0x2EFFFFFF),
    tileHighlight = Color(0x40FFFFFF),
    primary = Color.White,
    secondary = Color(0xE6FFFFFF),
    muted = Color(0xC2FFFFFF),
    rain = Color(0xFFFFF3B0),
    isDark = true,
    backgroundBrush = Brush.verticalGradient(listOf(Color(0xFF2A6CC0), Color(0xFF3F8BD8), Color(0xFF6AACE6))),
    glows = listOf(Glow(0.92f, 0.04f, 0.55f, Color(0x55FFF6D5))),
    curveColors = listOf(Color.White, Color(0xFFFFF3B0)),
    buttonBrush = SolidColor(Color.White),
    onButton = Color(0xFF2A6CC0),
    darkMap = false,
)

/** Pastel: cream background, peach → lavender card, tinted tiles, midnight blue text. */
val PastelSlate = SlatePalette(
    background = Color(0xFFFBF7F2),
    tile = Color.White,
    tileHighlight = Color(0xFFF1E9E1),
    primary = Color(0xFF2B2D42),
    secondary = Color(0xFF5C5F77),
    muted = Color(0xFF8E8FA3),
    rain = Color(0xFF3F7FE8),
    isDark = false,
    heroBrush = Brush.linearGradient(listOf(Color(0xFFFFD6A5), Color(0xFFFFB3B0), Color(0xFFCDBBFF))),
    tileTints = listOf(
        Color(0xFFFFE3D3), Color(0xFFD8F5E4), Color(0xFFDCEBFF),
        Color(0xFFE7DEFF), Color(0xFFFFF1C9), Color(0xFFD6F1F7),
    ),
    curveColors = listOf(Color(0xFFFF9F80), Color(0xFFB59CFF)),
    buttonBrush = Brush.horizontalGradient(listOf(Color(0xFFFFC98F), Color(0xFFFFADAD))),
    onButton = Color(0xFF2B2D42),
)

/** Aurora: indigo night, coral and purple glows, cyan accents. */
val AuroraSlate = SlatePalette(
    background = Color(0xFF1B1F4B),
    tile = Color(0x17FFFFFF),
    tileHighlight = Color(0x29FFFFFF),
    primary = Color.White,
    secondary = Color(0xFFC6C9F0),
    muted = Color(0xFF9195C9),
    rain = Color(0xFF5EE1FF),
    isDark = true,
    backgroundBrush = Brush.verticalGradient(listOf(Color(0xFF2A1B5C), Color(0xFF1B1F4B), Color(0xFF141733))),
    glows = listOf(
        Glow(0.95f, 0.02f, 0.75f, Color(0xB3FF8A65)),
        Glow(0.0f, 0.28f, 0.85f, Color(0x997C4DFF)),
    ),
    curveColors = listOf(Color(0xFFFF8A65), Color(0xFFB388FF), Color(0xFF5EE1FF)),
    buttonBrush = Brush.horizontalGradient(listOf(Color(0xFFFF8A65), Color(0xFFB388FF))),
    onButton = Color.White,
)

/**
 * Nature: the home screen sits on a zen landscape (mountains, lake, pines) that follows the weather
 * and the time of day; smoked glass tiles, white text. Elsewhere, a lake background at dusk.
 */
val NatureSlate = SlatePalette(
    background = Color(0xFF243049),
    tile = Color(0x5C0E1626),
    tileHighlight = Color(0x2EFFFFFF),
    primary = Color.White,
    secondary = Color(0xE6FFFFFF),
    muted = Color(0xBFFFFFFF),
    rain = Color(0xFFA8DBFF),
    isDark = true,
    backgroundBrush = Brush.verticalGradient(listOf(Color(0xFF1F2A44), Color(0xFF3A4C6B), Color(0xFF7E7F96))),
    glows = listOf(Glow(0.85f, 0.55f, 0.7f, Color(0x66F2A66E))),
    curveColors = listOf(Color(0xFFFFD7A8), Color.White, Color(0xFFA8DBFF)),
    buttonBrush = SolidColor(Color(0xF2FFFFFF)),
    onButton = Color(0xFF22304A),
    scenic = true,
)

val LocalSlate = staticCompositionLocalOf { DarkSlate }

/** Colours of the current theme (dark or light), usable in any composable. */
object Slate {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalSlate.current.background
    val Tile: Color @Composable @ReadOnlyComposable get() = LocalSlate.current.tile
    val TileHighlight: Color @Composable @ReadOnlyComposable get() = LocalSlate.current.tileHighlight
    val Primary: Color @Composable @ReadOnlyComposable get() = LocalSlate.current.primary
    val Secondary: Color @Composable @ReadOnlyComposable get() = LocalSlate.current.secondary
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalSlate.current.muted
    val Rain: Color @Composable @ReadOnlyComposable get() = LocalSlate.current.rain
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalSlate.current.isDark
    val HeroBrush: Brush? @Composable @ReadOnlyComposable get() = LocalSlate.current.heroBrush
    val TileTints: List<Color> @Composable @ReadOnlyComposable get() = LocalSlate.current.tileTints
    val CurveColors: List<Color> @Composable @ReadOnlyComposable get() = LocalSlate.current.curveColors
    val ButtonBrush: Brush @Composable @ReadOnlyComposable get() = LocalSlate.current.buttonBrush
    val OnButton: Color @Composable @ReadOnlyComposable get() = LocalSlate.current.onButton
    val DarkMap: Boolean @Composable @ReadOnlyComposable get() = LocalSlate.current.darkMap
    val Scenic: Boolean @Composable @ReadOnlyComposable get() = LocalSlate.current.scenic
}

/** Full-screen background of the current theme: colour or gradient, plus its glows. */
@Composable
fun Modifier.screenBackground(): Modifier {
    val palette = LocalSlate.current
    return drawBehind {
        drawRect(palette.backgroundBrush)
        palette.glows.forEach { glow ->
            val center = Offset(size.width * glow.x, size.height * glow.y)
            val radius = size.minDimension * glow.radius
            drawRect(Brush.radialGradient(listOf(glow.color, Color.Transparent), center, radius))
        }
    }
}

private fun SlatePalette.colorScheme() = if (isDark) {
    darkColorScheme(
        background = background, surface = tile, surfaceVariant = tileHighlight, primary = primary,
        onBackground = primary, onSurface = primary, onSurfaceVariant = secondary,
    )
} else {
    lightColorScheme(
        background = background, surface = tile, surfaceVariant = tileHighlight, primary = primary,
        onBackground = primary, onSurface = primary, onSurfaceVariant = secondary,
    )
}

private val typography = Typography(
    displayLarge = TextStyle(fontSize = 76.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = (-2).sp),
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Normal, lineHeight = 40.sp),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
)

/** User choice in the settings (variant of the Slate theme). */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** App theme; only Slate has System / Dark / Light variants. */
enum class ThemeStyle {
    SLATE, SKY, PASTEL, AURORA, NATURE;

    companion object {
        /** Theme until the user picks another one. */
        val DEFAULT = NATURE
    }

    fun palette(mode: ThemeMode, systemDark: Boolean): SlatePalette = when (this) {
        SLATE -> if (mode.isDark(systemDark)) DarkSlate else LightSlate
        SKY -> SkySlate
        PASTEL -> PastelSlate
        AURORA -> AuroraSlate
        NATURE -> NatureSlate
    }

    /** Widgets (Slate palettes): dark background only for the night themes. */
    fun widgetDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (this) {
        SLATE -> mode.isDark(systemDark)
        SKY, PASTEL -> false
        AURORA, NATURE -> true
    }
}

fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

@Composable
fun MeteoTheme(
    mode: ThemeMode = ThemeMode.DARK,
    style: ThemeStyle = ThemeStyle.SLATE,
    content: @Composable () -> Unit,
) {
    val palette = style.palette(mode, isSystemInDarkTheme())
    CompositionLocalProvider(LocalSlate provides palette) {
        MaterialTheme(colorScheme = palette.colorScheme(), typography = typography, content = content)
    }
}
