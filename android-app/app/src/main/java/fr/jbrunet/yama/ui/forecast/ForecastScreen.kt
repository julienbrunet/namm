package fr.jbrunet.yama.ui.forecast

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.jbrunet.yama.R
import fr.jbrunet.yama.data.repository.LocalForecast
import fr.jbrunet.yama.data.repository.StaleReason
import fr.jbrunet.yama.data.update.UpdateUiState
import fr.jbrunet.yama.domain.DailyForecast
import fr.jbrunet.yama.domain.PrecipitationStep
import fr.jbrunet.yama.domain.WeatherCondition
import fr.jbrunet.yama.ui.components.Pill
import fr.jbrunet.yama.ui.components.SectionLabel
import fr.jbrunet.yama.ui.components.TileShape
import fr.jbrunet.yama.ui.components.WeatherIcons
import fr.jbrunet.yama.ui.components.icon
import fr.jbrunet.yama.ui.currentLocale
import fr.jbrunet.yama.ui.formatDate
import fr.jbrunet.yama.ui.labelRes
import fr.jbrunet.yama.ui.theme.Slate
import fr.jbrunet.yama.ui.theme.screenBackground
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

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
                Text(stringResource(R.string.weather_unavailable), color = Slate.Primary)
                Text(stringResource(s.reason.labelRes), color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.action_retry), color = Slate.Primary) }
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.choose_city), color = Slate.Secondary) }
            }
            // Pull the screen down to refresh.
            is ForecastUiState.Success -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = viewModel::refresh,
                state = pullState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = refreshing,
                        modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars),
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
    if (Slate.Scenic) return NatureForecastContent(data, update, onOpenMap, onOpenSettings, onUpdateAction)
    val forecast = data.forecast
    val now = LocalDateTime.parse(forecast.current.time)
    val condition = WeatherCondition.fromWmo(forecast.current.weatherCode)
    val conditionLabel = stringResource(condition.labelRes)
    Box(Modifier.fillMaxSize().screenBackground()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        // Current weather: icon + temperature, then the place just below
        // (on a gradient card for the themes that have one).
        val hero = Slate.HeroBrush
        Column(
            if (hero == null) Modifier
            else Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(hero).padding(20.dp),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(condition.icon(), conditionLabel, tint = Slate.Primary, modifier = Modifier.size(72.dp))
            Spacer(Modifier.width(20.dp))
            Column {
                Text(
                    "${forecast.current.temperature.roundToInt()}°",
                    style = MaterialTheme.typography.displayLarge,
                    color = Slate.Primary,
                )
                Text(conditionLabel, style = MaterialTheme.typography.titleMedium, color = Slate.Secondary)
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
            Pill(longDate(forecast.current.time))
            // Failed update: tell how old the data is and why.
            data.staleReason?.let { StalePill(data.fetchedAtMillis, it) }
        }
        }

        UpdateBanner(update, onUpdateAction)

        Spacer(Modifier.height(20.dp))
        SectionLabel(stringResource(R.string.section_next_24h))
        HourlyTile(forecast.hours)

        Spacer(Modifier.height(16.dp))
        SectionLabel(stringResource(R.string.section_rain_2h))
        NowcastTile(forecast.nextPrecipitation, onOpenMap)

        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.section_now))
        CurrentTiles(data)

        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.section_7_days))
        Column(Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).padding(vertical = 6.dp)) {
            forecast.days.forEach { DayRow(it) }
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.home_footer),
                color = Slate.Muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(88.dp)) // room for the bottom bar
    }
        BottomBar(onOpenMap, onOpenSettings, Modifier.align(Alignment.BottomCenter))
    }
}

/** “Now” tiles: feels like, wind, humidity, rain today, max, min. */
@Composable
internal fun CurrentTiles(data: LocalForecast) {
    val c = data.forecast.current
    val today = data.forecast.days.firstOrNull()
    val locale = currentLocale()
    TileGrid(
        listOf(
            TileData(Icons.Outlined.Thermostat, "${c.apparentTemperature.roundToInt()}°", stringResource(R.string.tile_feels_like)),
            TileData(Icons.Outlined.Air, "${c.windSpeed.roundToInt()} km/h", stringResource(R.string.tile_wind)),
            TileData(Icons.Outlined.WaterDrop, "${c.humidity} %", stringResource(R.string.tile_humidity)),
            TileData(WeatherIcons.Rain, "${mm(today?.precipitationSum ?: 0.0, locale)} mm", stringResource(R.string.tile_rain_today)),
            TileData(Icons.Outlined.NorthEast, "${today?.tempMax?.roundToInt() ?: "–"}°", stringResource(R.string.tile_max)),
            TileData(Icons.Outlined.SouthEast, "${today?.tempMin?.roundToInt() ?: "–"}°", stringResource(R.string.tile_min)),
        ),
    )
}

/** “Friday, October 16 · 19:45” / “Vendredi 16 octobre · 19:45” */
@Composable
internal fun longDate(time: String): String =
    LocalContext.current.formatDate(R.string.date_long_pattern, LocalDateTime.parse(time), currentLocale()) +
        " · " + time.substringAfter('T')

/** Failed update: “data from 14:05 · no network”. */
@Composable
internal fun StalePill(fetchedAtMillis: Long, reason: StaleReason, modifier: Modifier = Modifier) {
    val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        .format(java.time.Instant.ofEpochMilli(fetchedAtMillis).atZone(java.time.ZoneId.systemDefault()))
    Pill(stringResource(R.string.stale_data, time, stringResource(reason.labelRes)), modifier)
}

/** Floating bar at the bottom of the screen, within thumb reach: rain map + settings. */
@Composable
internal fun BottomBar(onOpenMap: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier) {
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
            Text(stringResource(R.string.rain_map), color = Slate.OnButton, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpenSettings).padding(14.dp),
        ) {
            Icon(Icons.Outlined.Settings, stringResource(R.string.settings), tint = Slate.Primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun UpdateBanner(update: UpdateUiState, onAction: () -> Unit) {
    val (title, action, progress) = when (update) {
        UpdateUiState.None -> return
        is UpdateUiState.Available ->
            Triple(stringResource(R.string.update_available, update.update.version), stringResource(R.string.action_download), null)
        is UpdateUiState.Downloading -> Triple(stringResource(R.string.update_downloading, update.update.version), null, update.progress)
        is UpdateUiState.Ready ->
            Triple(stringResource(R.string.update_ready, update.update.version), stringResource(R.string.action_install), null)
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
internal fun NowcastTile(steps: List<PrecipitationStep>, onOpenMap: () -> Unit) {
    val max = (steps.maxOfOrNull { it.millimeters } ?: 0.0).coerceAtLeast(1.0)
    val total = steps.sumOf { it.millimeters }
    Column(
        Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).clickable(onClick = onOpenMap).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (total == 0.0) stringResource(R.string.no_rain_expected)
                else stringResource(R.string.rain_expected_mm, mm(total, currentLocale())),
                color = Slate.Primary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.see_map), color = Slate.Muted, style = MaterialTheme.typography.labelSmall)
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

internal data class TileData(val icon: ImageVector, val value: String, val label: String)

@Composable
internal fun TileGrid(tiles: List<TileData>) {
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
internal fun DayRow(day: DailyForecast) {
    val date = LocalDate.parse(day.date)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            date.dayOfWeek.getDisplayName(TextStyle.SHORT, currentLocale()).replaceFirstChar { it.uppercase() },
            color = Slate.Primary,
            modifier = Modifier.width(56.dp),
        )
        Icon(WeatherCondition.fromWmo(day.weatherCode).icon(), null, tint = Slate.Secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.weight(1f))
        Text(
            if (day.precipitationSum > 0) "${mm(day.precipitationSum, currentLocale())} mm" else "",
            color = Slate.Rain,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(72.dp),
        )
        Text("${day.tempMin.roundToInt()}°", color = Slate.Secondary, modifier = Modifier.width(36.dp))
        Text("${day.tempMax.roundToInt()}°", color = Slate.Primary, modifier = Modifier.width(36.dp))
    }
}


/** Millimetres: one decimal (language separator) under 10 mm, whole number above. */
internal fun mm(value: Double, locale: Locale): String =
    if (value < 10) "%.1f".format(locale, value) else value.roundToInt().toString()
