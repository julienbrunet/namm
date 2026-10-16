package com.namm.meteo.ui.map

import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.namm.meteo.domain.MapFrame
import com.namm.meteo.domain.Place
import com.namm.meteo.domain.RainMap
import com.namm.meteo.ui.components.RoundIconButton
import com.namm.meteo.ui.components.TileShape
import com.namm.meteo.ui.theme.Slate
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HourFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun MapScreen(viewModel: MapViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        MapUiState.Loading -> Box(Modifier.fillMaxSize().background(Slate.Background), Alignment.Center) {
            CircularProgressIndicator(color = Slate.Secondary)
        }
        is MapUiState.Error -> Box(Modifier.fillMaxSize().background(Slate.Background), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Carte indisponible", color = Slate.Primary)
                Text(s.message, color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { viewModel.load() }) { Text("Réessayer", color = Slate.Primary) }
                TextButton(onClick = onBack) { Text("Retour", color = Slate.Secondary) }
            }
        }
        is MapUiState.Ready -> MapContent(s, onBack, viewModel::select, viewModel::togglePlay)
    }
}

@Composable
fun MapContent(
    state: MapUiState.Ready,
    onBack: () -> Unit = {},
    onSelect: (Int) -> Unit = {},
    onTogglePlay: () -> Unit = {},
    online: Boolean = true,
) {
    Box(Modifier.fillMaxSize().background(Slate.Background)) {
        RainMapView(state.place, state.map, state.map.frames.getOrNull(state.index), online)
        Box(Modifier.padding(20.dp)) { RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Retour", onBack) }
        Timeline(state, onSelect, onTogglePlay, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun RainMapView(place: Place, map: RainMap, frame: MapFrame?, online: Boolean) {
    val context = LocalContext.current
    val gridOverlay = remember(map) { ForecastGridOverlay(map.grid) }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(OsmTiles)
            overlayManager.tilesOverlay.setColorFilter(SlateMapFilter)
            setUseDataConnection(online)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 5.0
            maxZoomLevel = 12.0
            controller.setZoom(8.0) // la zone de prévision (~190 km) remplit l'écran
            controller.setCenter(GeoPoint(place.latitude, place.longitude))
            isTilesScaledToDpi = true
            setBackgroundColor(0xFF2F333D.toInt())
            // Le filtre ardoise inverse les couleurs : un fond clair devient ardoise sombre.
            overlayManager.tilesOverlay.loadingBackgroundColor = 0xFFD8D8DC.toInt()
            overlayManager.tilesOverlay.loadingLineColor = 0xFFD8D8DC.toInt()
            overlays.add(Marker(this).apply {
                position = GeoPoint(place.latitude, place.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = place.name
                icon = androidx.core.content.ContextCompat.getDrawable(context, com.namm.meteo.R.drawable.map_marker)
            })
        }
    }
    val radarOverlay = remember { RadarOverlay(mapView) }
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
                        is MapFrame.Forecast -> "Prévision AROME / ICON"
                        null -> "Aucune donnée"
                    },
                    color = Slate.Secondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Legend()
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
                Text("passé", color = Slate.Muted, style = MaterialTheme.typography.labelSmall)
                Text("maintenant", color = Slate.Muted, style = MaterialTheme.typography.labelSmall)
                Text("+6 h", color = Slate.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(0.1, 0.4, 1.0, 3.0, 5.0).forEach {
            Box(Modifier.size(width = 10.dp, height = 18.dp).clip(RoundedCornerShape(3.dp)).background(Color(rainColor(it)!!)))
        }
    }
    Spacer(Modifier.height(0.dp))
}
