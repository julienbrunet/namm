package fr.jbrunet.yama.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.jbrunet.yama.MeteoApp
import fr.jbrunet.yama.data.repository.Bounds
import fr.jbrunet.yama.data.repository.ForecastRepository
import fr.jbrunet.yama.data.repository.LoadProgress
import fr.jbrunet.yama.data.repository.RainMapRepository
import fr.jbrunet.yama.data.repository.staleReason
import fr.jbrunet.yama.domain.Place
import fr.jbrunet.yama.domain.RainMap
import fr.jbrunet.yama.widget.WidgetMapRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import fr.jbrunet.yama.domain.MapFrame
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
        /** Pendant la lecture : transition en cours vers l'image suivante (null à l'arrêt). */
        val blend: Blend? = null,
    ) : MapUiState
    data class Error(val reason: fr.jbrunet.yama.data.repository.StaleReason) : MapUiState
}

/**
 * Image intermédiaire de l'animation : [t] ∈ ]0, 1[ entre l'image courante et [next] ;
 * [tween] = prévision interpolée (null entre deux images radar : fondu enchaîné).
 */
data class Blend(val next: MapFrame, val t: Float, val tween: MapFrame.Forecast?) {
    /** Heure affichée, qui défile en continu. */
    fun time(from: MapFrame): Long = from.time + ((next.time - from.time) * t).toLong()
}

/** Couche « Vent » : masquée par défaut, chargée au premier affichage. */
data class WindUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val frames: List<fr.jbrunet.yama.domain.WindFrame> = emptyList(),
    val loadedAt: Long = 0,
    val failed: Boolean = false,
)

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
                if (frames.isEmpty()) throw predicted.exceptionOrNull() ?: IllegalStateException("No rain data")
                val map = RainMap(grid, frames)
                rainMaps.progress.value = LoadProgress(fr.jbrunet.yama.data.repository.LoadStage.PREPARING)
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
            (_state.value as? MapUiState.Ready)?.copy(refreshing = false) ?: MapUiState.Error(it.staleReason())
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

    private val _wind = MutableStateFlow(WindUiState())
    val wind: StateFlow<WindUiState> = _wind

    /** Affiche / masque le vent ; le télécharge (un appel Open-Meteo) s'il manque ou date de plus de 30 min. */
    fun toggleWind() {
        val w = _wind.value
        if (w.visible) {
            _wind.value = w.copy(visible = false)
            return
        }
        _wind.value = w.copy(visible = true, failed = false)
        if (w.frames.isNotEmpty() && clock() - w.loadedAt < 30 * 60_000L) return
        val s = _state.value as? MapUiState.Ready ?: return
        viewModelScope.launch {
            _wind.value = _wind.value.copy(loading = true)
            _wind.value = runCatching { rainMaps.wind(s.map.grid) }.fold(
                { _wind.value.copy(loading = false, frames = it, loadedAt = clock()) },
                { _wind.value.copy(loading = false, failed = true) },
            )
        }
    }

    fun select(index: Int) {
        val s = _state.value as? MapUiState.Ready ?: return
        _state.value = s.copy(index = index.coerceIn(0, s.map.frames.lastIndex), blend = null)
    }

    fun togglePlay() {
        val s = _state.value as? MapUiState.Ready ?: return
        if (s.playing) {
            player?.cancel()
            _state.value = s.copy(playing = false, blend = null)
            return
        }
        _state.value = s.copy(playing = true)
        player = viewModelScope.launch {
            while (isActive) {
                val start = _state.value as? MapUiState.Ready ?: break
                val from = start.map.frames.getOrNull(start.index) ?: break
                val nextIndex = (start.index + 1) % start.map.frames.size
                val to = start.map.frames[nextIndex]
                // Entre deux prévisions de la même grille : images intermédiaires (pluie déplacée).
                val tweenable = from is MapFrame.Forecast && to is MapFrame.Forecast && from.grid == to.grid && nextIndex != 0
                // Entre deux radars : fondu enchaîné, dessiné directement par la carte.
                val fade = from is MapFrame.Radar && to is MapFrame.Radar
                if (tweenable || fade) {
                    val motion = if (tweenable) {
                        withContext(Dispatchers.Default) { FrameInterpolation.motion(from as MapFrame.Forecast, to as MapFrame.Forecast) }
                    } else null
                    for (step in 1 until STEPS) {
                        delay(FRAME_MS / STEPS)
                        val t = step.toFloat() / STEPS
                        val tween = motion?.let { m ->
                            withContext(Dispatchers.Default) {
                                FrameInterpolation.between(from as MapFrame.Forecast, to as MapFrame.Forecast, t, m)
                                    .also { ForecastBitmaps.get(it) } // image prête avant l'affichage
                            }
                        }
                        val cur = _state.value as? MapUiState.Ready ?: return@launch
                        if (!cur.playing || cur.index != start.index || cur.map !== start.map) break
                        _state.value = cur.copy(blend = Blend(to, t, tween))
                    }
                    delay(FRAME_MS / STEPS)
                } else {
                    delay(FRAME_MS)
                }
                val cur = _state.value as? MapUiState.Ready ?: break
                if (!cur.playing) break
                _state.value = cur.copy(index = (cur.index + 1) % cur.map.frames.size, blend = null)
            }
        }
    }

    companion object {
        const val REFRESH_MS = 10 * 60 * 1000L

        /** Durée d'affichage d'un pas de 15 min pendant la lecture, découpée en [STEPS] images. */
        const val FRAME_MS = 700L
        const val STEPS = 6

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
