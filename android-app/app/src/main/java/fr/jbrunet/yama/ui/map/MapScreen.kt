package fr.jbrunet.yama.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import fr.jbrunet.yama.data.repository.Bounds
import fr.jbrunet.yama.data.repository.LoadProgress
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import fr.jbrunet.yama.R
import fr.jbrunet.yama.domain.MapFrame
import fr.jbrunet.yama.domain.Place
import fr.jbrunet.yama.domain.RainMap
import fr.jbrunet.yama.ui.components.RoundIconButton
import fr.jbrunet.yama.ui.components.TileShape
import fr.jbrunet.yama.ui.labelRes
import fr.jbrunet.yama.ui.theme.Slate
import fr.jbrunet.yama.ui.theme.screenBackground
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HourFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun MapScreen(viewModel: MapViewModel, place: Place?, onBack: () -> Unit) {
    val configuration = LocalConfiguration.current
    LaunchedEffect(place) { viewModel.show(place, configuration.screenWidthDp, configuration.screenHeightDp) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val wind by viewModel.wind.collectAsStateWithLifecycle()
    // While the screen is visible (and every time it comes back to the foreground), check
    // every minute whether the data is older than 10 min and reload it if needed.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refreshIfStale()
                delay(60_000)
            }
        }
    }
    when (val s = state) {
        MapUiState.Loading -> Box(Modifier.fillMaxSize().screenBackground(), Alignment.Center) {
            LoadingSteps(progress)
        }
        is MapUiState.Error -> Box(Modifier.fillMaxSize().screenBackground(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.map_unavailable), color = Slate.Primary)
                Text(stringResource(s.reason.labelRes), color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { viewModel.load() }) { Text(stringResource(R.string.action_retry), color = Slate.Primary) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back), color = Slate.Secondary) }
            }
        }
        is MapUiState.Ready -> MapContent(
            s, onBack, viewModel::select, viewModel::togglePlay, { viewModel.load() }, viewModel::onViewportChanged,
            progress = progress,
            wind = wind,
            onToggleWind = viewModel::toggleWind,
        )
    }
}

@Composable
fun MapContent(
    state: MapUiState.Ready,
    onBack: () -> Unit = {},
    onSelect: (Int) -> Unit = {},
    onTogglePlay: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onViewportChanged: (Bounds) -> Unit = {},
    online: Boolean = true,
    progress: LoadProgress? = null,
    wind: WindUiState = WindUiState(),
    onToggleWind: () -> Unit = {},
) {
    val frame = state.map.frames.getOrNull(state.index)
    val windFrame = if (wind.visible && frame != null) wind.frames.at(frame.time) else null
    Box(Modifier.fillMaxSize().screenBackground()) {
        RainMapView(state.place, state.map, frame, online, onViewportChanged, windFrame, state.blend)
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), onBack)
                Spacer(Modifier.weight(1f))
                RefreshPill(state.loadedAt, state.refreshing, onRefresh, progress)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (wind.visible && (wind.loading || wind.failed || wind.frames.isEmpty())) WindLegend(wind)
                Spacer(Modifier.width(8.dp))
                WindButton(wind.visible, onToggleWind)
            }
        }
        Timeline(state, onSelect, onTogglePlay, Modifier.align(Alignment.BottomCenter), windShown = windFrame != null)
    }
}

@Composable
private fun RainMapView(
    place: Place,
    map: RainMap,
    frame: MapFrame?,
    online: Boolean,
    onViewportChanged: (Bounds) -> Unit,
    windFrame: fr.jbrunet.yama.domain.WindFrame? = null,
    blend: Blend? = null,
) {
    val context = LocalContext.current
    val gridOverlay = remember { ForecastGridOverlay() }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(OsmTiles)
            setUseDataConnection(online)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 5.0
            maxZoomLevel = 12.0
            controller.setZoom(MapViewModel.INITIAL_ZOOM.toDouble())
            controller.setCenter(GeoPoint(place.latitude, place.longitude))
            isTilesScaledToDpi = true
            overlays.add(Marker(this).apply {
                position = GeoPoint(place.latitude, place.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = place.name
                icon = androidx.core.content.ContextCompat.getDrawable(context, fr.jbrunet.yama.R.drawable.map_marker)
            })
        }
    }
    val radarOverlay = remember { RadarOverlay(mapView) }
    val windOverlay = remember { WindOverlay(context.resources.displayMetrics.density) }
    // Observed clouds: satellite shared by the app (common cache); absent in previews / tests.
    val satelliteOverlay = remember {
        (context.applicationContext as? fr.jbrunet.yama.MeteoApp)?.let { SatelliteOverlay(mapView, it.satellite) }
    }
    LaunchedEffect(place.latitude, place.longitude, map) {
        satelliteOverlay?.box = fr.jbrunet.yama.data.satellite.MercatorBox.around(place.latitude, place.longitude, 400.0)
        if (online) satelliteOverlay?.prefetch(map.frames.filterIsInstance<MapFrame.Radar>().map { it.time })
    }
    // Dark theme: OSM tiles darkened by the slate filter; light theme: original tiles.
    val dark = Slate.DarkMap
    val background = Slate.Tile.toArgb()
    LaunchedEffect(dark) {
        mapView.setBackgroundColor(background)
        mapView.overlayManager.tilesOverlay.apply {
            setColorFilter(if (dark) SlateMapFilter else FadedMapFilter)
            // The filter inverts colours: in dark mode, a light background becomes dark slate.
            loadingBackgroundColor = if (dark) 0xFFD8D8DC.toInt() else 0xFFE9EBEF.toInt()
            loadingLineColor = loadingBackgroundColor
        }
        gridOverlay.outlineColor = if (dark) 0x55F2F3F5 else 0x662B2F38
        gridOverlay.cloudPaint = cloudPaint(dark)
        satelliteOverlay?.paint = cloudPaint(dark)
        windOverlay.haloColor = if (dark) 0x99353A45.toInt() else 0xCCF8F9FA.toInt()
        mapView.invalidate()
    }
    val viewportCallback by rememberUpdatedState(onViewportChanged)
    DisposableEffect(mapView) {
        fun report() = mapView.boundingBox.let {
            viewportCallback(Bounds(it.latSouth, it.lonWest, it.latNorth, it.lonEast))
        }
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?) = true.also { report() }
            override fun onZoom(event: ZoomEvent?) = true.also { report() }
        }
        mapView.addMapListener(listener)
        onDispose { mapView.removeMapListener(listener) }
    }
    // Re-centre the map if the place shown changes (another saved place, new position).
    LaunchedEffect(place.latitude, place.longitude) {
        mapView.controller.setCenter(GeoPoint(place.latitude, place.longitude))
        (mapView.overlays.firstOrNull { it is Marker } as? Marker)?.position = GeoPoint(place.latitude, place.longitude)
    }
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onDetach() }
    }
    AndroidView({ mapView }, Modifier.fillMaxSize()) { view ->
        // A single rain layer visible: radar (past) or forecast grid (future).
        view.overlays.removeAll { it is RadarOverlay || it is ForecastGridOverlay || it is SatelliteOverlay || it is WindOverlay }
        // Wind map: shown alone (no rain or clouds) to stay readable.
        if (windFrame != null) {
            windOverlay.frame = windFrame
            view.overlays.add(0, windOverlay)
        } else when (frame) {
            is MapFrame.Radar -> {
                radarOverlay.frame = frame
                radarOverlay.next = blend?.next as? MapFrame.Radar
                radarOverlay.mix = blend?.t ?: 0f
                view.overlays.add(0, radarOverlay)
                satelliteOverlay?.let { it.time = frame.time; view.overlays.add(0, it) } // clouds under the rain
            }
            is MapFrame.Forecast -> {
                gridOverlay.frame = blend?.tween ?: frame
                view.overlays.add(0, gridOverlay)
            }
            null -> Unit
        }
        view.invalidate()
    }
}

@Composable
private fun Timeline(state: MapUiState.Ready, onSelect: (Int) -> Unit, onTogglePlay: () -> Unit, modifier: Modifier, windShown: Boolean = false) {
    val frames = state.map.frames
    val frame = frames.getOrNull(state.index)
    Column(
        modifier.fillMaxWidth().padding(16.dp).clip(TileShape).background(Slate.Background.copy(alpha = 0.95f)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(
                if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                stringResource(if (state.playing) R.string.action_pause else R.string.action_play),
                onTogglePlay,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    frame?.let { HourFormat.format(Instant.ofEpochSecond(state.blend?.time(it) ?: it.time).atZone(ZoneId.systemDefault())) } ?: "--:--",
                    color = Slate.Primary,
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    stringResource(
                        if (windShown) R.string.map_wind_source else when (frame) {
                            is MapFrame.Radar -> R.string.map_radar_source
                            is MapFrame.Forecast -> frame.source.labelRes
                            null -> R.string.map_no_data
                        },
                    ),
                    color = Slate.Secondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (frames.size > 1) {
            Slider(
                value = state.index.toFloat(),
                onValueChange = { onSelect(it.toInt()) },
                valueRange = 0f..frames.lastIndex.toFloat(),
                steps = (frames.size - 2).coerceAtLeast(0),
                colors = SliderDefaults.colors(
                    thumbColor = Slate.Primary,
                    activeTrackColor = Slate.Rain,
                    inactiveTrackColor = Slate.TileHighlight,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.timeline_radar), color = Slate.Muted, style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.timeline_forecast), color = Slate.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (windShown) WindScale() else Legend()
    }
}

/** Wind legend: speed levels (km/h) of the arrows. */
@Composable
private fun WindScale() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        WindLevel.entries.forEach { level ->
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(level.color)))
                Text(
                    stringResource(if (level == WindLevel.entries.last()) R.string.wind_level_max else R.string.wind_level, level.label),
                    color = Slate.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                )
            }
        }
    }
}

/** Legend shared by radar / forecast: one box per level, labels under the key levels. */
@Composable
private fun Legend() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RainLevel.entries.forEach { level ->
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(level.color)))
                Text(
                    level.labelRes?.let { stringResource(it) }.orEmpty(),
                    color = Slate.Muted,
                    // No letter spacing: “moderate” fits in its box.
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** “Updated at HH:mm” pill: tapping it forces a reload. */
@Composable
private fun RefreshPill(loadedAt: Long, refreshing: Boolean, onRefresh: () -> Unit, progress: LoadProgress? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Slate.Background.copy(alpha = 0.92f))
            .clickable(enabled = !refreshing, onClick = onRefresh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (refreshing) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Slate.Primary, strokeWidth = 2.dp)
        } else {
            Icon(Icons.Outlined.Refresh, stringResource(R.string.action_refresh), tint = Slate.Primary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (refreshing) progress?.let { progressText(it) } ?: stringResource(R.string.map_updating)
            else stringResource(R.string.map_updated_at, HourFormat.format(Instant.ofEpochMilli(loadedAt).atZone(ZoneId.systemDefault()))),
            color = Slate.Secondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** “Météo-France forecast · 12 / 24” */
@Composable
private fun progressText(p: LoadProgress): String {
    val label = stringResource(p.stage.labelRes)
    return if (p.total > 0) stringResource(R.string.progress_steps, label, p.done, p.total)
    else stringResource(R.string.progress_running, label)
}

/** Waiting screen: current step, and a progress bar once the number of images is known. */
@Composable
fun LoadingSteps(progress: LoadProgress?) {
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.rain_map), color = Slate.Primary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        val p = progress
        if (p != null && p.total > 0) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { p.done.toFloat() / p.total },
                modifier = Modifier.width(220.dp),
                color = Slate.Rain,
                trackColor = Slate.Tile,
            )
        } else {
            androidx.compose.material3.LinearProgressIndicator(Modifier.width(220.dp), color = Slate.Rain, trackColor = Slate.Tile)
        }
        Spacer(Modifier.height(12.dp))
        Text(p?.let { progressText(it) } ?: stringResource(R.string.map_loading_first), color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** “Wind” button: layer hidden by default, highlighted when shown. */
@Composable
private fun WindButton(visible: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (visible) Slate.Rain else Slate.Tile)
            .clickable(onClick = onClick),
        Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Air,
            stringResource(if (visible) R.string.hide_wind else R.string.show_wind),
            tint = if (visible) Slate.Background else Slate.Primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Wind legend (km/h) or loading state. */
@Composable
private fun WindLegend(wind: WindUiState) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Slate.Background.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            wind.loading -> {
                CircularProgressIndicator(Modifier.size(14.dp), color = Slate.Primary, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.wind_loading), color = Slate.Secondary, style = MaterialTheme.typography.labelSmall)
            }
            wind.failed -> Text(stringResource(R.string.wind_unavailable), color = Slate.Secondary, style = MaterialTheme.typography.labelSmall)
            else -> {
                WindLevel.entries.forEach { level ->
                    Box(Modifier.size(width = 14.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(level.color)))
                    Spacer(Modifier.width(3.dp))
                    Text(level.label, color = Slate.Secondary, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(R.string.unit_kmh), color = Slate.Secondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
