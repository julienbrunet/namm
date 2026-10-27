package com.namm.meteo.ui.forecast

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.SouthEast
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.namm.meteo.data.repository.LocalForecast
import com.namm.meteo.data.update.UpdateUiState
import com.namm.meteo.domain.DailyForecast
import com.namm.meteo.domain.DayPhase
import com.namm.meteo.domain.SunPosition
import com.namm.meteo.domain.WeatherCondition
import com.namm.meteo.domain.sunPosition
import com.namm.meteo.ui.components.Pill
import com.namm.meteo.ui.components.SectionLabel
import com.namm.meteo.ui.components.TileShape
import com.namm.meteo.ui.components.WeatherIcons
import com.namm.meteo.ui.components.icon
import com.namm.meteo.ui.theme.Slate
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle as DateStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val French = Locale.FRENCH

/** Ombre douce : texte blanc lisible sur un ciel clair. */
private val SoftShadow = Shadow(Color(0x66000000), Offset(0f, 2f), 14f)

/**
 * Accueil du thème Nature : le paysage occupe tout l'écran et suit la météo et l'heure ;
 * la météo actuelle se lit dans le ciel, le détail sur des tuiles en verre fumé au-dessus du lac.
 */
@Composable
fun NatureForecastContent(
    data: LocalForecast,
    update: UpdateUiState,
    onOpenMap: () -> Unit,
    onOpenSettings: () -> Unit,
    onUpdateAction: () -> Unit,
) {
    val forecast = data.forecast
    val c = forecast.current
    val condition = WeatherCondition.fromWmo(c.weatherCode)
    val today = forecast.days.firstOrNull()
    val sun = sunPosition(c.time, today?.sunrise, today?.sunset)
    Box(Modifier.fillMaxSize().background(Slate.Background)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // En-tête : le paysage (montagnes et reflet dans le lac) occupe le haut de l'écran ;
            // la météo actuelle se lit à gauche, le paysage reste dégagé à droite.
            Box(Modifier.fillMaxWidth()) {
                NatureScene(condition, sun, Modifier.matchParentSize())
                // Fondu du bas du paysage vers le fond de page, sous lequel arrivent les tuiles.
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(0.72f to Color.Transparent, 1f to Slate.Background),
                    ),
                )
                Column(
                    Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 28.dp),
                ) {
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpenSettings).padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Place, null, tint = Slate.Primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            data.place.name,
                            style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Normal, shadow = SoftShadow),
                            color = Slate.Primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        longDate(c.time),
                        style = MaterialTheme.typography.bodyMedium.copy(shadow = SoftShadow),
                        color = Slate.Secondary,
                        modifier = Modifier.padding(start = 26.dp),
                    )
                    Text(
                        "${c.temperature.roundToInt()}°",
                        style = TextStyle(fontSize = 92.sp, fontWeight = FontWeight.Thin, letterSpacing = (-3).sp, shadow = SoftShadow),
                        color = Slate.Primary,
                    )
                    Text(
                        // « Ensoleillé » n'a pas de sens la nuit.
                        if (condition == WeatherCondition.CLEAR && sun.phase == DayPhase.NIGHT) "Nuit claire" else condition.label,
                        style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Light, shadow = SoftShadow),
                        color = Slate.Primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "↑ ${today?.tempMax?.roundToInt() ?: "–"}°   ↓ ${today?.tempMin?.roundToInt() ?: "–"}°",
                        style = MaterialTheme.typography.titleMedium.copy(shadow = SoftShadow),
                        color = Slate.Secondary,
                    )
                    // Mise à jour échouée : on dit de quand datent les données et pourquoi.
                    data.staleReason?.let { reason ->
                        val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                            .format(java.time.Instant.ofEpochMilli(data.fetchedAtMillis).atZone(java.time.ZoneId.systemDefault()))
                        Spacer(Modifier.height(10.dp))
                        Pill("données de $time · ${reason.label}")
                    }
                }
            }

            // Tuiles : même organisation que les autres thèmes, plus la course du soleil.
            Column(Modifier.padding(horizontal = 22.dp)) {
                UpdateBanner(update, onUpdateAction)
                SectionLabel("PROCHAINES 24 H")
                HourlyTile(forecast.hours)

                Spacer(Modifier.height(16.dp))
                SectionLabel("PLUIE DANS LES 2 H")
                NowcastTile(forecast.nextPrecipitation, onOpenMap)

                Spacer(Modifier.height(24.dp))
                SectionLabel("MAINTENANT")
                TileGrid(
                    listOf(
                        TileData(Icons.Outlined.Thermostat, "${c.apparentTemperature.roundToInt()}°", "ressenti"),
                        TileData(Icons.Outlined.Air, "${c.windSpeed.roundToInt()} km/h", "vent"),
                        TileData(Icons.Outlined.WaterDrop, "${c.humidity} %", "humidité"),
                        TileData(WeatherIcons.Rain, "${mm(today?.precipitationSum ?: 0.0)} mm", "pluie du jour"),
                        TileData(Icons.Outlined.NorthEast, "${today?.tempMax?.roundToInt() ?: "–"}°", "max"),
                        TileData(Icons.Outlined.SouthEast, "${today?.tempMin?.roundToInt() ?: "–"}°", "min"),
                    ),
                )

                if (today?.sunrise != null && today.sunset != null) {
                    Spacer(Modifier.height(24.dp))
                    SectionLabel("LEVER ET COUCHER DU SOLEIL")
                    SunArc(sun, today.sunrise.substringAfter('T'), today.sunset.substringAfter('T'))
                }

                Spacer(Modifier.height(24.dp))
                SectionLabel("7 JOURS")
                Column(Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).padding(vertical = 6.dp)) {
                    forecast.days.forEach { DayRow(it) }
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    "Données Open-Meteo · sans publicité",
                    color = Slate.Muted,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(88.dp)) // place pour la barre du bas
            }
        }
        BottomBar(onOpenMap, onOpenSettings, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
    }
}

/** Course du soleil : arc pointillé du lever au coucher, position actuelle marquée. */
@Composable
private fun SunArc(sun: SunPosition, sunrise: String, sunset: String) {
    val track = Slate.TileHighlight
    val light = Slate.CurveColors.first()
    val primary = Slate.Primary
    Column(Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            val left = Offset(size.width * 0.08f, size.height - 4.dp.toPx())
            val radiusX = size.width * 0.42f
            val radiusY = size.height - 10.dp.toPx()
            drawLine(track, Offset(0f, left.y), Offset(size.width, left.y), 1.dp.toPx())
            drawArc(
                track,
                180f,
                180f,
                useCenter = false,
                topLeft = Offset(left.x, left.y - radiusY),
                size = Size(radiusX * 2, radiusY * 2),
                style = Stroke(1.5f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))),
            )
            val p = sun.progress.coerceIn(0f, 1f)
            val angle = PI * (1 - p)
            val point = Offset(
                left.x + radiusX + radiusX * cos(angle).toFloat(),
                left.y - radiusY * sin(angle).toFloat(),
            )
            if (sun.progress in 0f..1f) {
                drawCircle(Brush.radialGradient(listOf(light.copy(alpha = 0.6f), Color.Transparent), point, 18.dp.toPx()), 18.dp.toPx(), point)
                drawCircle(light, 6.dp.toPx(), point)
            } else {
                drawCircle(primary.copy(alpha = 0.5f), 4.dp.toPx(), point, style = Stroke(1.5f.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("Lever $sunrise", color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("Coucher $sunset", color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** « Vendredi 16 octobre · 19:45 » */
private fun longDate(time: String): String {
    val now = LocalDateTime.parse(time)
    return now.dayOfWeek.getDisplayName(DateStyle.FULL, French).replaceFirstChar { it.uppercase() } +
        " ${now.dayOfMonth} " + now.month.getDisplayName(DateStyle.FULL, French) + " · " + time.substringAfter('T')
}
