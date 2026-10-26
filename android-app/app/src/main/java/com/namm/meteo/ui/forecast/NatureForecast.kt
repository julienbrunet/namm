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
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val shadeDistance = constraints.maxHeight * 0.6f
        NatureScene(
            condition,
            sun,
            // Le paysage s'assombrit en faisant défiler : les tuiles restent lisibles.
            Modifier.fillMaxSize().scrollShade { (scroll.value / shadeDistance).coerceIn(0f, 1f) * 0.45f },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 22.dp),
        ) {
            // Premier écran : la météo dans le ciel, le paysage dégagé, les tuiles qui affleurent.
            Column(Modifier.height(screenHeight * 0.74f).padding(top = 20.dp)) {
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
                Spacer(Modifier.height(12.dp))
                Text(
                    "${c.temperature.roundToInt()}°",
                    style = TextStyle(fontSize = 112.sp, fontWeight = FontWeight.Thin, letterSpacing = (-4).sp, shadow = SoftShadow),
                    color = Slate.Primary,
                )
                Text(
                    // « Ensoleillé » n'a pas de sens la nuit.
                    if (condition == WeatherCondition.CLEAR && sun.phase == DayPhase.NIGHT) "Nuit claire" else condition.label,
                    style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Light, shadow = SoftShadow),
                    color = Slate.Primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "↑ ${today?.tempMax?.roundToInt() ?: "–"}°   ↓ ${today?.tempMin?.roundToInt() ?: "–"}°   ·   ressenti ${c.apparentTemperature.roundToInt()}°",
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

            UpdateBanner(update, onUpdateAction)
            Spacer(Modifier.height(16.dp))
            SectionLabel("PLUIE DANS LES 2 H")
            NowcastTile(forecast.nextPrecipitation, onOpenMap)

            Spacer(Modifier.height(20.dp))
            SectionLabel("PROCHAINES 24 H")
            HourlyTile(forecast.hours)

            Spacer(Modifier.height(20.dp))
            SectionLabel("EN CE MOMENT")
            Row(Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).padding(vertical = 16.dp)) {
                Stat(Icons.Outlined.Air, "${c.windSpeed.roundToInt()} km/h", "vent")
                Divider()
                Stat(Icons.Outlined.WaterDrop, "${c.humidity} %", "humidité")
                Divider()
                Stat(WeatherIcons.Rain, "${mm(today?.precipitationSum ?: 0.0)} mm", "pluie du jour")
            }

            if (today?.sunrise != null && today.sunset != null) {
                Spacer(Modifier.height(20.dp))
                SectionLabel("SOLEIL")
                SunArc(sun, today.sunrise.substringAfter('T'), today.sunset.substringAfter('T'))
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("7 JOURS")
            val weekMin = forecast.days.minOfOrNull { it.tempMin } ?: 0.0
            val weekMax = forecast.days.maxOfOrNull { it.tempMax } ?: 0.0
            Column(Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).padding(vertical = 6.dp)) {
                forecast.days.forEach { RangeDayRow(it, weekMin, weekMax) }
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
        BottomBar(onOpenMap, onOpenSettings, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
    }
}

/** Voile noir dont l'opacité est lue au moment du dessin (pas de recomposition au défilement). */
private fun Modifier.scrollShade(alpha: () -> Float) = drawWithContent {
    drawContent()
    drawRect(Color.Black.copy(alpha = alpha()))
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Stat(icon: ImageVector, value: String, label: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Slate.Secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(8.dp))
        Text(value, color = Slate.Primary, style = MaterialTheme.typography.titleMedium)
        Text(label, color = Slate.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(64.dp).background(Slate.TileHighlight))
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

/** Jour de la semaine avec une barre min → max placée sur l'amplitude de la semaine. */
@Composable
private fun RangeDayRow(day: DailyForecast, weekMin: Double, weekMax: Double) {
    val date = LocalDate.parse(day.date)
    val track = Slate.TileHighlight
    val colors = Slate.CurveColors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            date.dayOfWeek.getDisplayName(DateStyle.SHORT, French).replaceFirstChar { it.uppercase() },
            color = Slate.Primary,
            modifier = Modifier.width(48.dp),
        )
        Icon(WeatherCondition.fromWmo(day.weatherCode).icon(), null, tint = Slate.Primary, modifier = Modifier.size(20.dp))
        Text(
            if (day.precipitationSum > 0) "${mm(day.precipitationSum)} mm" else "",
            color = Slate.Rain,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(60.dp),
        )
        Text("${day.tempMin.roundToInt()}°", color = Slate.Secondary, textAlign = TextAlign.End, modifier = Modifier.width(32.dp))
        Canvas(Modifier.weight(1f).height(6.dp).padding(horizontal = 10.dp)) {
            val range = (weekMax - weekMin).coerceAtLeast(1.0)
            val start = ((day.tempMin - weekMin) / range).toFloat() * size.width
            val end = ((day.tempMax - weekMin) / range).toFloat() * size.width
            val stroke = size.height
            drawLine(track, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke, StrokeCap.Round)
            drawLine(
                Brush.horizontalGradient(colors.reversed(), 0f, size.width),
                Offset(start, stroke / 2),
                Offset(end.coerceAtLeast(start + 1f), stroke / 2),
                stroke,
                StrokeCap.Round,
            )
        }
        Text("${day.tempMax.roundToInt()}°", color = Slate.Primary, modifier = Modifier.width(32.dp))
    }
}

/** « Vendredi 16 octobre · 19:45 » */
private fun longDate(time: String): String {
    val now = LocalDateTime.parse(time)
    return now.dayOfWeek.getDisplayName(DateStyle.FULL, French).replaceFirstChar { it.uppercase() } +
        " ${now.dayOfMonth} " + now.month.getDisplayName(DateStyle.FULL, French) + " · " + time.substringAfter('T')
}
