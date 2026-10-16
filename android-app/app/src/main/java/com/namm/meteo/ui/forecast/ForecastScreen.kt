package com.namm.meteo.ui.forecast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FilterDrama
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Dehaze
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namm.meteo.domain.DailyForecast
import com.namm.meteo.domain.Forecast
import com.namm.meteo.domain.PrecipitationStep
import com.namm.meteo.domain.WeatherCondition
import com.namm.meteo.ui.theme.Slate
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private val TileShape = RoundedCornerShape(18.dp)
private val French = Locale.FRENCH

@Composable
fun ForecastScreen(viewModel: ForecastViewModel = viewModel(factory = ForecastViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(
        Modifier.fillMaxSize().background(Slate.Background).safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            ForecastUiState.Loading -> CircularProgressIndicator(color = Slate.Secondary)
            is ForecastUiState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Météo indisponible", color = Slate.Primary)
                Text(s.message, color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = viewModel::retry) { Text("Réessayer", color = Slate.Primary) }
            }
            is ForecastUiState.Success -> ForecastContent(s.city, s.forecast)
        }
    }
}

@Composable
fun ForecastContent(city: String, forecast: Forecast) {
    val now = LocalDateTime.parse(forecast.current.time)
    val condition = WeatherCondition.fromWmo(forecast.current.weatherCode)
    Column(
        Modifier
            .fillMaxSize()
            .background(Slate.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        // En-tête façon écran d'accueil : salutation + date.
        Text(if (now.hour >= 18) "Bonsoir," else "Bonjour,", style = MaterialTheme.typography.headlineLarge, color = Slate.Primary)
        Text(
            now.dayOfWeek.getDisplayName(TextStyle.FULL, French).replaceFirstChar { it.uppercase() } +
                ", ${now.dayOfMonth}",
            style = MaterialTheme.typography.headlineLarge,
            color = Slate.Secondary,
        )
        Text(
            now.month.getDisplayName(TextStyle.FULL, French).replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.headlineLarge,
            color = Slate.Secondary,
        )
        Spacer(Modifier.height(10.dp))
        Pill("$city · ${forecast.current.time.substringAfter('T')}")

        Spacer(Modifier.height(36.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${forecast.current.temperature.roundToInt()}°",
                style = MaterialTheme.typography.displayLarge,
                color = Slate.Primary,
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Icon(condition.icon(), null, tint = Slate.Primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(4.dp))
                Text(condition.label, color = Slate.Secondary)
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("PLUIE DANS LES 2 H")
        NowcastTile(forecast.nextPrecipitation)

        Spacer(Modifier.height(24.dp))
        SectionLabel("MAINTENANT")
        val today = forecast.days.firstOrNull()
        val c = forecast.current
        TileGrid(
            listOf(
                TileData(Icons.Outlined.Thermostat, "${c.apparentTemperature.roundToInt()}°", "ressenti"),
                TileData(Icons.Outlined.Air, "${c.windSpeed.roundToInt()} km/h", "vent"),
                TileData(Icons.Outlined.WaterDrop, "${c.humidity} %", "humidité"),
                TileData(Icons.Outlined.Umbrella, "${mm(today?.precipitationSum ?: 0.0)} mm", "pluie du jour"),
                TileData(Icons.Outlined.WbSunny, "${today?.tempMax?.roundToInt() ?: "–"}°", "max"),
                TileData(Icons.Outlined.Cloud, "${today?.tempMin?.roundToInt() ?: "–"}°", "min"),
            ),
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel("7 JOURS")
        Column(
            Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).padding(vertical = 6.dp),
        ) {
            forecast.days.forEach { DayRow(it) }
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Données Open-Meteo · sans publicité", color = Slate.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Pill(text: String) {
    Text(
        text,
        color = Slate.Secondary,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Slate.TileHighlight)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Slate.Muted,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(bottom = 10.dp, start = 2.dp),
    )
}

@Composable
private fun NowcastTile(steps: List<PrecipitationStep>) {
    val max = (steps.maxOfOrNull { it.millimeters } ?: 0.0).coerceAtLeast(1.0)
    val total = steps.sumOf { it.millimeters }
    Column(Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).padding(16.dp)) {
        Text(
            if (total == 0.0) "Pas de pluie prévue" else "${mm(total)} mm attendus",
            color = Slate.Primary,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            steps.forEach { step ->
                val ratio = (step.millimeters / max).toFloat().coerceIn(0.06f, 1f)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(ratio)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (step.millimeters > 0) Slate.Rain else Slate.TileHighlight),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, steps.size / 2, steps.size - 1).filter { it in steps.indices }.forEach {
                Text(steps[it].time.substringAfter('T'), color = Slate.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private data class TileData(val icon: ImageVector, val value: String, val label: String)

@Composable
private fun TileGrid(tiles: List<TileData>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile ->
                    Column(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(TileShape)
                            .background(Slate.Tile)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Icon(tile.icon, null, tint = Slate.Primary, modifier = Modifier.size(20.dp))
                        Column {
                            Text(tile.value, color = Slate.Primary, style = MaterialTheme.typography.titleMedium)
                            Text(tile.label, color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayRow(day: DailyForecast) {
    val date = LocalDate.parse(day.date)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            date.dayOfWeek.getDisplayName(TextStyle.SHORT, French).replaceFirstChar { it.uppercase() },
            color = Slate.Primary,
            modifier = Modifier.width(56.dp),
        )
        Icon(WeatherCondition.fromWmo(day.weatherCode).icon(), null, tint = Slate.Secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.weight(1f))
        Text(
            if (day.precipitationSum > 0) "${mm(day.precipitationSum)} mm" else "",
            color = Slate.Rain,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(72.dp),
        )
        Text("${day.tempMin.roundToInt()}°", color = Slate.Secondary, modifier = Modifier.width(36.dp))
        Text("${day.tempMax.roundToInt()}°", color = Slate.Primary, modifier = Modifier.width(36.dp))
    }
}

private fun mm(value: Double): String =
    if (value < 10) "%.1f".format(French, value) else value.roundToInt().toString()

private fun WeatherCondition.icon(): ImageVector = when (this) {
    WeatherCondition.CLEAR -> Icons.Outlined.WbSunny
    WeatherCondition.PARTLY_CLOUDY -> Icons.Outlined.FilterDrama
    WeatherCondition.CLOUDY -> Icons.Outlined.Cloud
    WeatherCondition.FOG -> Icons.Outlined.Dehaze
    WeatherCondition.DRIZZLE -> Icons.Outlined.Grain
    WeatherCondition.RAIN -> Icons.Outlined.Umbrella
    WeatherCondition.SNOW -> Icons.Outlined.AcUnit
    WeatherCondition.STORM -> Icons.Outlined.Thunderstorm
}
