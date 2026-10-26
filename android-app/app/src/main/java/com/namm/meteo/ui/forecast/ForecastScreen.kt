package com.namm.meteo.ui.forecast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SouthEast
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.namm.meteo.data.repository.LocalForecast
import com.namm.meteo.data.update.UpdateUiState
import com.namm.meteo.domain.DailyForecast
import com.namm.meteo.domain.PrecipitationStep
import com.namm.meteo.domain.WeatherCondition
import com.namm.meteo.ui.components.Pill
import com.namm.meteo.ui.components.SectionLabel
import com.namm.meteo.ui.components.TileShape
import com.namm.meteo.ui.components.WeatherIcons
import com.namm.meteo.ui.components.icon
import com.namm.meteo.ui.theme.Slate
import com.namm.meteo.ui.theme.screenBackground
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private val French = Locale.FRENCH

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForecastScreen(
    viewModel: ForecastViewModel,
    onOpenMap: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val pullState = rememberPullToRefreshState()
    Box(Modifier.fillMaxSize().screenBackground(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            ForecastUiState.Loading -> CircularProgressIndicator(color = Slate.Secondary)
            is ForecastUiState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Météo indisponible", color = Slate.Primary)
                Text(s.message, color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = viewModel::refresh) { Text("Réessayer", color = Slate.Primary) }
                TextButton(onClick = onOpenSettings) { Text("Choisir une ville", color = Slate.Secondary) }
            }
            // Tirer l'écran vers le bas pour actualiser.
            is ForecastUiState.Success -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = viewModel::refresh,
                state = pullState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = Slate.TileHighlight,
                        color = Slate.Primary,
                    )
                },
            ) {
                ForecastContent(
                    data = s.data,
                    update = update,
                    onOpenMap = onOpenMap,
                    onOpenSettings = onOpenSettings,
                    onUpdateAction = viewModel::onUpdateAction,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ForecastContent(
    data: LocalForecast,
    update: UpdateUiState = UpdateUiState.None,
    onOpenMap: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onUpdateAction: () -> Unit = {},
) {
    val forecast = data.forecast
    val now = LocalDateTime.parse(forecast.current.time)
    val condition = WeatherCondition.fromWmo(forecast.current.weatherCode)
    Box(Modifier.fillMaxSize().screenBackground()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        // Météo actuelle : icône + température, puis le lieu juste en dessous
        // (sur une carte dégradée pour les thèmes qui en ont une).
        val hero = Slate.HeroBrush
        Column(
            if (hero == null) Modifier
            else Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(hero).padding(20.dp),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(condition.icon(), condition.label, tint = Slate.Primary, modifier = Modifier.size(72.dp))
            Spacer(Modifier.width(20.dp))
            Column {
                Text(
                    "${forecast.current.temperature.roundToInt()}°",
                    style = MaterialTheme.typography.displayLarge,
                    color = Slate.Primary,
                )
                Text(condition.label, style = MaterialTheme.typography.titleMedium, color = Slate.Secondary)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpenSettings).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Place, null, tint = Slate.Secondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                data.place.name,
                style = MaterialTheme.typography.headlineLarge,
                color = Slate.Primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(
                now.dayOfWeek.getDisplayName(TextStyle.FULL, French).replaceFirstChar { it.uppercase() } +
                    " ${now.dayOfMonth} " + now.month.getDisplayName(TextStyle.FULL, French) +
                    " · " + forecast.current.time.substringAfter('T'),
            )
            // Mise à jour échouée : on dit de quand datent les données et pourquoi.
            data.staleReason?.let { reason ->
                val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                    .format(java.time.Instant.ofEpochMilli(data.fetchedAtMillis).atZone(java.time.ZoneId.systemDefault()))
                Pill("données de $time · ${reason.label}")
            }
        }
        }

        UpdateBanner(update, onUpdateAction)

        Spacer(Modifier.height(20.dp))
        SectionLabel("PROCHAINES 24 H")
        HourlyTile(forecast.hours)

        Spacer(Modifier.height(16.dp))
        SectionLabel("PLUIE DANS LES 2 H")
        NowcastTile(forecast.nextPrecipitation, onOpenMap)

        Spacer(Modifier.height(24.dp))
        SectionLabel("MAINTENANT")
        val today = forecast.days.firstOrNull()
        val c = forecast.current
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

        Spacer(Modifier.height(24.dp))
        SectionLabel("7 JOURS")
        Column(Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).padding(vertical = 6.dp)) {
            forecast.days.forEach { DayRow(it) }
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "Données Open-Meteo · sans publicité",
                color = Slate.Muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(88.dp)) // place pour la barre du bas
    }
        BottomBar(onOpenMap, onOpenSettings, Modifier.align(Alignment.BottomCenter))
    }
}

/** Barre flottante en bas d'écran, à portée de pouce : carte de pluie + paramètres. */
@Composable
private fun BottomBar(onOpenMap: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(50))
            .background(Slate.TileHighlight)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Slate.ButtonBrush)
                .clickable(onClick = onOpenMap)
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Map, null, tint = Slate.OnButton, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Carte de pluie", color = Slate.OnButton, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpenSettings).padding(14.dp),
        ) {
            Icon(Icons.Outlined.Settings, "Paramètres", tint = Slate.Primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun UpdateBanner(update: UpdateUiState, onAction: () -> Unit) {
    val (title, action, progress) = when (update) {
        UpdateUiState.None -> return
        is UpdateUiState.Available -> Triple("Version ${update.update.version} disponible", "Télécharger", null)
        is UpdateUiState.Downloading -> Triple("Téléchargement de la version ${update.update.version}…", null, update.progress)
        is UpdateUiState.Ready -> Triple("Version ${update.update.version} prête", "Installer", null)
    }
    Spacer(Modifier.height(20.dp))
    Column(Modifier.fillMaxWidth().clip(TileShape).background(Slate.TileHighlight).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SystemUpdate, null, tint = Slate.Rain, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, color = Slate.Primary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null) TextButton(onClick = onAction) { Text(action, color = Slate.Rain) }
        }
        if (progress != null) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = Slate.Rain,
                trackColor = Slate.Tile,
            )
        }
    }
}

@Composable
private fun NowcastTile(steps: List<PrecipitationStep>, onOpenMap: () -> Unit) {
    val max = (steps.maxOfOrNull { it.millimeters } ?: 0.0).coerceAtLeast(1.0)
    val total = steps.sumOf { it.millimeters }
    Column(
        Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).clickable(onClick = onOpenMap).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (total == 0.0) "Pas de pluie prévue" else "${mm(total)} mm attendus",
                color = Slate.Primary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text("Voir la carte", color = Slate.Muted, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().height(40.dp),
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
        tiles.chunked(3).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { i, tile ->
                    Column(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(TileShape)
                            .background(Slate.TileTints.getOrElse(r * 3 + i) { Slate.Tile })
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


internal fun mm(value: Double): String =
    if (value < 10) "%.1f".format(French, value) else value.roundToInt().toString()
