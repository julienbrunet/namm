package com.namm.meteo.ui.theme

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Palette d'un thème : fond et tuiles très proches, texte contrasté, bleu pluie. */
@Immutable
data class SlatePalette(
    val background: Color,
    val tile: Color,
    val tileHighlight: Color,
    val primary: Color,
    val secondary: Color,
    val muted: Color,
    val rain: Color,
    val isDark: Boolean,
)

/** Thème sombre « ardoise » (design d'origine). */
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

/** Thème clair : même sobriété, gris perle et texte ardoise. */
val LightSlate = SlatePalette(
    background = Color(0xFFE9EBEF),
    tile = Color(0xFFF8F9FA),
    tileHighlight = Color(0xFFDADDE3),
    primary = Color(0xFF454B57),
    secondary = Color(0xFF6E7480),
    muted = Color(0xFF979CA6),
    rain = Color(0xFF3F7CC4),
    isDark = false,
)

val LocalSlate = staticCompositionLocalOf { DarkSlate }

/** Couleurs du thème courant (sombre ou clair), utilisables dans tout composable. */
object Slate {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalSlate.current.background
    val Tile: Color @Composable @ReadOnlyComposable get() = LocalSlate.current.tile
    val TileHighlight: Color @Composable @ReadOnlyComposable get() = LocalSlate.current.tileHighlight
    val Primary: Color @Composable @ReadOnlyComposable get() = LocalSlate.current.primary
    val Secondary: Color @Composable @ReadOnlyComposable get() = LocalSlate.current.secondary
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalSlate.current.muted
    val Rain: Color @Composable @ReadOnlyComposable get() = LocalSlate.current.rain
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalSlate.current.isDark
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

/** Choix de l'utilisateur dans les paramètres. */
enum class ThemeMode(val label: String) { SYSTEM("Système"), DARK("Sombre"), LIGHT("Clair") }

@Composable
fun MeteoTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val palette = if (dark) DarkSlate else LightSlate
    CompositionLocalProvider(LocalSlate provides palette) {
        MaterialTheme(colorScheme = palette.colorScheme(), typography = typography, content = content)
    }
}
