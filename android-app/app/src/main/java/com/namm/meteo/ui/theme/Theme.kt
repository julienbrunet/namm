package com.namm.meteo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Palette sobre « ardoise » : fond et tuiles très proches, texte blanc / gris.
object Slate {
    val Background = Color(0xFF353A45)
    val Tile = Color(0xFF2F333D)
    val TileHighlight = Color(0xFF3D424E)
    val Primary = Color(0xFFF2F3F5)
    val Secondary = Color(0xFF9AA0AC)
    val Muted = Color(0xFF6B717D)
    val Rain = Color(0xFF8FB3D9)
}

private val colors = darkColorScheme(
    background = Slate.Background,
    surface = Slate.Tile,
    surfaceVariant = Slate.TileHighlight,
    primary = Slate.Primary,
    onBackground = Slate.Primary,
    onSurface = Slate.Primary,
    onSurfaceVariant = Slate.Secondary,
)

private val typography = Typography(
    displayLarge = TextStyle(fontSize = 88.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = (-2).sp),
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Normal, lineHeight = 40.sp),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
)

@Composable
fun MeteoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
