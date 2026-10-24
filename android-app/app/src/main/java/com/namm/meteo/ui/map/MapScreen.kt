package com.namm.meteo.ui.map

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
import com.namm.meteo.data.repository.Bounds
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.namm.meteo.domain.MapFrame
import com.namm.meteo.domain.Place
import com.namm.meteo.domain.RainMap
import com.namm.meteo.ui.components.RoundIconButton
import com.namm.meteo.ui.components.TileShape
import com.namm.meteo.ui.theme.Slate
import com.namm.meteo.ui.theme.screenBackground
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HourFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun MapScreen(viewModel: MapViewModel, place: Place?, onBack: () -> Unit) {
    LaunchedEffect(place) { viewModel.show(place) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Tant que l'écran est visible (et à chaque retour au premier plan), on vérifie
    // chaque minute si les données ont plus de 10 min et on les recharge si besoin.
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
            CircularProgressIndicator(color = Slate.Secondary)
        }
        is MapUiState.Error -> Box(Modifier.fillMaxSize().screenBackground(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Carte indisponible", color = Slate.Primary)
                Text(s.message, color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { viewModel.load() }) { Text("Réessayer", color = Slate.Primary) }
                TextButton(onClick = onBack) { Text("Retour", color = Slate.Secondary) }
            }
        }
        is MapUiState.Ready -> MapContent(
            s, onBack, viewModel::select, viewModel::togglePlay, { viewModel.load() }, viewModel::onViewportChanged,
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
) {
    Box(Modifier.fillMaxSize().screenBackground()) {
        RainMapView(state.place, state.map, state.map.frames.getOrNull(state.index), online, onViewportChanged)
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Retour", onBack)
            Spacer(Modifier.weight(1f))
            RefreshPill(state.loadedAt, state.refreshing, onRefresh)
        }
        Timeline(state, onSelect, onTogglePlay, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun RainMapView(
    place: Place,
    map: RainMap,
    frame: MapFrame?,
    online: Boolean,
    onViewportChanged: (Bounds) -> Unit,
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
            controller.setZoom(8.0) // la zone de prévision (~190 km) remplit l'écran
            controller.setCenter(GeoPoint(place.latitude, place.longitude))
            isTilesScaledToDpi = true
            overlays.add(Marker(this).apply {
                position = GeoPoint(place.latitude, place.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = place.name
                icon = androidx.core.content.ContextCompat.getDrawable(context, com.namm.meteo.R.drawable.map_marker)
            })
        }
    }
    val radarOverlay = remember { RadarOverlay(mapView) }
    // Thème sombre : tuiles OSM assombries par le filtre ardoise ; thème clair : tuiles d'origine.
    val dark = Slate.DarkMap
    val background = Slate.Tile.toArgb()
    LaunchedEffect(dark) {
        mapView.setBackgroundColor(background)
        mapView.overlayManager.tilesOverlay.apply {
            setColorFilter(if (dark) SlateMapFilter else null)
            // Le filtre inverse les couleurs : en sombre, un fond clair devient ardoise sombre.
            loadingBackgroundColor = if (dark) 0xFFD8D8DC.toInt() else 0xFFE9EBEF.toInt()
            loadingLineColor = loadingBackgroundColor
        }
        gridOverlay.outlineColor = if (dark) 0x55F2F3F5 else 0x662B2F38
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
    // Recentre la carte si le lieu affiché change (autre lieu enregistré, nouvelle position).
    LaunchedEffect(place.latitude, place.longitude) {
        mapView.controller.setCenter(GeoPoint(place.latitude, place.longitude))
        (mapView.overlays.firstOrNull { it is Marker } as? Marker)?.position = GeoPoint(place.latitude, place.longitude)
    }
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onDetach() }
    }
    AndroidView({ mapView }, Modifier.fillMaxSize()) { view ->
        // Une seule couche de pluie visible : radar (passé) ou grille de prévision (futur).
        view.overlays.removeAll { it is RadarOverlay || it is ForecastGridOverlay }
        when (frame) {
            is MapFrame.Radar -> {
                radarOverlay.frame = frame
                view.overlays.add(0, radarOverlay)
            }
            is MapFrame.Forecast -> {
                gridOverlay.frame = frame
                view.overlays.add(0, gridOverlay)
            }
            null -> Unit
        }
        view.invalidate()
    }
}

@Composable
private fun Timeline(state: MapUiState.Ready, onSelect: (Int) -> Unit, onTogglePlay: () -> Unit, modifier: Modifier) {
    val frames = state.map.frames
    val frame = frames.getOrNull(state.index)
    Column(
        modifier.fillMaxWidth().padding(16.dp).clip(TileShape).background(Slate.Background.copy(alpha = 0.95f)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(
                if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                if (state.playing) "Pause" else "Lecture",
                onTogglePlay,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    frame?.let { HourFormat.format(Instant.ofEpochSecond(it.time).atZone(ZoneId.systemDefault())) } ?: "--:--",
                    color = Slate.Primary,
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    when (frame) {
                        is MapFrame.Radar -> "Radar observé"
                        is MapFrame.Forecast -> frame.source.label
                        null -> "Aucune donnée"
                    },
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
                Text("radar −2 h", color = Slate.Muted, style = MaterialTheme.typography.labelSmall)
                Text("prévision +24 h", color = Slate.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        Legend()
    }
}

/** Légende commune radar / prévision : une case par palier, libellés sous les paliers clés. */
@Composable
private fun Legend() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RainLevel.entries.forEach { level ->
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(level.color)))
                Text(level.label, color = Slate.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

/** Pastille « Mis à jour à HH:mm » : un appui force le rechargement. */
@Composable
private fun RefreshPill(loadedAt: Long, refreshing: Boolean, onRefresh: () -> Unit) {
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
            Icon(Icons.Outlined.Refresh, "Actualiser", tint = Slate.Primary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (refreshing) "Mise à jour…"
            else "Mis à jour à " + HourFormat.format(Instant.ofEpochMilli(loadedAt).atZone(ZoneId.systemDefault())),
            color = Slate.Secondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
