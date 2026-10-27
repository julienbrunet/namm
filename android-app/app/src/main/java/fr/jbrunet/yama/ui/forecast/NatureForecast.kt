package fr.jbrunet.yama.ui.forecast

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
import androidx.compose.material.icons.outlined.Place
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.jbrunet.yama.R
import fr.jbrunet.yama.data.repository.LocalForecast
import fr.jbrunet.yama.data.update.UpdateUiState
import fr.jbrunet.yama.domain.DayPhase
import fr.jbrunet.yama.domain.SunPosition
import fr.jbrunet.yama.domain.WeatherCondition
import fr.jbrunet.yama.domain.sunPosition
import fr.jbrunet.yama.ui.components.SectionLabel
import fr.jbrunet.yama.ui.components.TileShape
import fr.jbrunet.yama.ui.components.icon
import fr.jbrunet.yama.ui.labelRes
import fr.jbrunet.yama.ui.theme.Slate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Soft shadow: white text readable on a bright sky. */
private val SoftShadow = Shadow(Color(0x66000000), Offset(0f, 2f), 14f)

/**
 * Nature theme home screen: the landscape fills the screen and follows the weather and the time;
 * the current weather reads in the sky, the details on smoked glass tiles above the lake.
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
            // Header: the landscape (mountains and their reflection in the lake) fills the top of the screen;
            // the current weather reads on the left, the landscape stays clear on the right.
            Box(Modifier.fillMaxWidth()) {
                NatureScene(condition, sun, Modifier.matchParentSize())
                // Fade from the bottom of the landscape to the page background, under which the tiles come.
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
                        // “Sunny” makes no sense at night.
                        stringResource(
                            if (condition == WeatherCondition.CLEAR && sun.phase == DayPhase.NIGHT) R.string.condition_clear_night
                            else condition.labelRes,
                        ),
                        style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Light, shadow = SoftShadow),
                        color = Slate.Primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "↑ ${today?.tempMax?.roundToInt() ?: "–"}°   ↓ ${today?.tempMin?.roundToInt() ?: "–"}°",
                        style = MaterialTheme.typography.titleMedium.copy(shadow = SoftShadow),
                        color = Slate.Secondary,
                    )
                    // Failed update: tell how old the data is and why.
                    data.staleReason?.let {
                        Spacer(Modifier.height(10.dp))
                        StalePill(data.fetchedAtMillis, it)
                    }
                }
            }

            // Tiles: same layout as the other themes, plus the sun's course.
            Column(Modifier.padding(horizontal = 22.dp)) {
                UpdateBanner(update, onUpdateAction)
                SectionLabel(stringResource(R.string.section_next_24h))
                HourlyTile(forecast.hours)

                Spacer(Modifier.height(16.dp))
                SectionLabel(stringResource(R.string.section_rain_2h))
                NowcastTile(forecast.nextPrecipitation, onOpenMap)

                Spacer(Modifier.height(24.dp))
                SectionLabel(stringResource(R.string.section_now))
                CurrentTiles(data)

                if (today?.sunrise != null && today.sunset != null) {
                    Spacer(Modifier.height(24.dp))
                    SectionLabel(stringResource(R.string.section_sun))
                    SunArc(sun, today.sunrise.substringAfter('T'), today.sunset.substringAfter('T'))
                }

                Spacer(Modifier.height(24.dp))
                SectionLabel(stringResource(R.string.section_7_days))
                Column(Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).padding(vertical = 6.dp)) {
                    forecast.days.forEach { DayRow(it) }
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.home_footer),
                    color = Slate.Muted,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(88.dp)) // room for the bottom bar
            }
        }
        BottomBar(onOpenMap, onOpenSettings, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
    }
}

/** Sun's course: dotted arc from sunrise to sunset, current position marked. */
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
            Text(stringResource(R.string.sunrise_at, sunrise), color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.sunset_at, sunset), color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
