package com.namm.meteo.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.namm.meteo.MeteoApp
import com.namm.meteo.data.repository.Bounds
import com.namm.meteo.data.repository.ForecastRepository
import com.namm.meteo.data.repository.LoadProgress
import com.namm.meteo.data.repository.RainMapRepository
import com.namm.meteo.domain.Place
import com.namm.meteo.domain.RainMap
import com.namm.meteo.widget.WidgetMapRenderer
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface MapUiState {
    data object Loading : MapUiState
    data class Ready(
        val place: Place,
        val map: RainMap,
        val index: Int,
        val playing: Boolean = false,
        /** Heure du dernier téléchargement (ms). */
        val loadedAt: Long = 0,
        val refreshing: Boolean = false,
        /** Prévision incomplète (Open-Meteo saturé) : nouvel essai dès la minute suivante. */
        val partial: Boolean = false,
    ) : MapUiState
    data class Error(val message: String) : MapUiState
}

class MapViewModel(
    private val forecasts: ForecastRepository,
    private val rainMaps: RainMapRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow<MapUiState>(MapUiState.Loading)
    val state: StateFlow<MapUiState> = _state
    private var player: Job? = null
    private var viewportJob: Job? = null

    /** Lieu affiché sur l'accueil (position ou lieu enregistré), fourni par l'écran. */
    private var place: Place? = null

    /** Dernière zone visible de la carte, pour y adapter la grille de prévision. */
    private var viewport: Bounds? = null

    /** Taille de l'écran (dp), pour connaître la zone visible avant que la carte n'existe. */
    private var screenDp: Pair<Int, Int>? = null

    /**
     * Affiche la carte centrée sur ce lieu (null : lieu résolu via les paramètres) ; recharge s'il a changé.
     * [screenWidthDp] × [screenHeightDp] : taille de la carte, pour charger d'emblée la prévision sur
     * toute la zone visible (sinon un premier carré réduit puis un second chargement).
     */
    fun show(target: Place?, screenWidthDp: Int = 0, screenHeightDp: Int = 0) {
        if (screenWidthDp > 0 && screenHeightDp > 0) screenDp = screenWidthDp to screenHeightDp
        val current = place
        if (target != null) place = target
        if (_state.value !is MapUiState.Ready || (target != null && (current == null || !current.sameAs(target)))) {
            viewport = null
            load()
        } else {
            refreshIfStale()
        }
    }

    /**
     * La carte a bougé (déplacement / zoom) : après une courte pause, on recharge la prévision
     * sur la zone visible si la grille actuelle ne la couvre plus ou n'a plus la bonne finesse.
     */
    fun onViewportChanged(bounds: Bounds) {
        viewport = bounds
        viewportJob?.cancel()
        viewportJob = viewModelScope.launch {
            delay(400)
            val s = _state.value as? MapUiState.Ready ?: return@launch
            if (!RainMapRepository.needsNewGrid(s.map.grid, bounds)) return@launch
            // Zone déjà vue récemment : réutilisée sans appel réseau.
            rainMaps.cachedFor(bounds)?.let { (grid, forecast) ->
                val map = s.map.withForecast(grid, forecast)
                ForecastBitmaps.prepare(map.frames)
                _state.value = s.copy(map = map, index = s.index.coerceIn(0, map.frames.lastIndex))
                return@launch
            }
            val grid = RainMapRepository.gridFor(bounds)
            _state.value = s.copy(refreshing = true)
            val forecast = runCatching { rainMaps.forecastFor(grid) }.getOrNull()
            val cur = _state.value as? MapUiState.Ready ?: return@launch
            _state.value = if (forecast == null || forecast.frames.isEmpty()) {
                cur.copy(refreshing = false)
            } else {
                val map = cur.map.withForecast(grid, forecast.frames)
                ForecastBitmaps.prepare(map.frames)
                cur.copy(map = map, index = cur.index.coerceIn(0, map.frames.lastIndex), refreshing = false, partial = forecast.partial)
            }
        }
    }

    /** Étape du chargement en cours, affichée sur l'écran d'attente et la pastille. */
    val progress: StateFlow<LoadProgress?> = rainMaps.progress

    fun load() = viewModelScope.launch {
        val previous = _state.value as? MapUiState.Ready
        if (previous == null) _state.value = MapUiState.Loading else _state.value = previous.copy(refreshing = true)
        _state.value = runCatching {
            coroutineScope {
                val place = place ?: forecasts.resolvePlace()
                val grid = (viewport ?: initialViewport(place))?.let(RainMapRepository::gridFor)
                    ?: RainMapRepository.gridAround(place)
                val forecast = async { runCatching { rainMaps.forecastFor(grid) } }
                val radar = rainMaps.radar()
                // Premier affichage : la carte et le radar apparaissent sans attendre la prévision.
                if (previous == null && radar.isNotEmpty() && !forecast.isCompleted) {
                    _state.value = MapUiState.Ready(place, RainMap(grid, radar), radar.lastIndex, loadedAt = clock(), refreshing = true)
                }
                val predicted = forecast.await()
                val frames = radar + predicted.getOrNull()?.frames.orEmpty()
                if (frames.isEmpty()) throw predicted.exceptionOrNull() ?: IllegalStateException("Aucune donnée de pluie")
                val map = RainMap(grid, frames)
                rainMaps.progress.value = LoadProgress("Préparation des images")
                ForecastBitmaps.prepare(map.frames) // images calculées hors du thread principal
                // On reste sur « maintenant » si l'utilisateur y était, sinon sur la même heure.
                val shown = _state.value as? MapUiState.Ready ?: previous
                val index = when {
                    shown == null || shown.index == shown.map.nowIndex || shown === _state.value && shown.index == shown.map.frames.lastIndex -> map.nowIndex
                    else -> map.frames.indexOfFirst { it.time >= shown.map.frames[shown.index].time }
                        .takeIf { it >= 0 } ?: map.nowIndex
                }
                MapUiState.Ready(
                    place, map, index, playing = shown?.playing ?: false, loadedAt = clock(),
                    partial = predicted.getOrNull()?.partial ?: true,
                )
            }
        }.getOrElse {
            (_state.value as? MapUiState.Ready)?.copy(refreshing = false) ?: MapUiState.Error(it.message ?: "Erreur réseau")
        }
        rainMaps.progress.value = null
    }

    /**
     * Zone visible à l'ouverture (carte centrée sur le lieu, zoom [INITIAL_ZOOM]). Tuiles à l'échelle
     * de l'écran (isTilesScaledToDpi) : une tuile de 256 px fait 256 dp, d'où un calcul en dp.
     */
    private fun initialViewport(place: Place): Bounds? = screenDp?.let { (w, h) ->
        WidgetMapRenderer.bounds(place.latitude, place.longitude, w, h, INITIAL_ZOOM)
    }

    /** Recharge si les données ont plus de [REFRESH_MS] (radar mis à jour toutes les 10 min). */
    fun refreshIfStale() {
        val s = _state.value
        // Prévision incomplète (Open-Meteo saturé) : on réessaie dès la minute suivante.
        val maxAge = if (s is MapUiState.Ready && s.partial) 60_000L else REFRESH_MS
        if (s is MapUiState.Ready && !s.refreshing && clock() - s.loadedAt >= maxAge) load()
        if (s is MapUiState.Error) load()
    }

    fun select(index: Int) {
        val s = _state.value as? MapUiState.Ready ?: return
        _state.value = s.copy(index = index.coerceIn(0, s.map.frames.lastIndex))
    }

    fun togglePlay() {
        val s = _state.value as? MapUiState.Ready ?: return
        if (s.playing) {
            player?.cancel()
            _state.value = s.copy(playing = false)
            return
        }
        _state.value = s.copy(playing = true)
        player = viewModelScope.launch {
            while (isActive) {
                delay(700)
                val cur = _state.value as? MapUiState.Ready ?: break
                _state.value = cur.copy(index = (cur.index + 1) % cur.map.frames.size)
            }
        }
    }

    companion object {
        const val REFRESH_MS = 10 * 60 * 1000L

        /** Zoom à l'ouverture de la carte. */
        const val INITIAL_ZOOM = 8

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MeteoApp
                MapViewModel(app.forecastRepository, app.rainMapRepository)
            }
        }
    }
}
