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
        /** Time of the last download (ms). */
        val loadedAt: Long = 0,
        val refreshing: Boolean = false,
        /** Incomplete forecast (Open-Meteo rate limit): retry from the next minute. */
        val partial: Boolean = false,
        /** While playing: transition in progress towards the next frame (null when stopped). */
        val blend: Blend? = null,
    ) : MapUiState
    data class Error(val reason: fr.jbrunet.yama.data.repository.StaleReason) : MapUiState
}

/**
 * Intermediate animation frame: [t] ∈ ]0, 1[ between the current frame and [next];
 * [tween] = interpolated forecast (null between two radar frames: cross-fade).
 */
data class Blend(val next: MapFrame, val t: Float, val tween: MapFrame.Forecast?) {
    /** Displayed time, scrolling continuously. */
    fun time(from: MapFrame): Long = from.time + ((next.time - from.time) * t).toLong()
}

/** “Wind” layer: hidden by default, loaded when first shown. */
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

    /** Place shown on the home screen (position or saved place), provided by the screen. */
    private var place: Place? = null

    /** Last visible area of the map, to fit the forecast grid to it. */
    private var viewport: Bounds? = null

    /** Screen size (dp), to know the visible area before the map exists. */
    private var screenDp: Pair<Int, Int>? = null

    /**
     * Shows the map centred on this place (null: place resolved from the settings); reloads if it changed.
     * [screenWidthDp] × [screenHeightDp]: map size, to load the forecast over the whole visible area
     * straight away (otherwise a first smaller square, then a second load).
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
     * The map moved (pan / zoom): after a short pause, reload the forecast over the visible
     * area if the current grid no longer covers it or no longer has the right resolution.
     */
    fun onViewportChanged(bounds: Bounds) {
        viewport = bounds
        viewportJob?.cancel()
        viewportJob = viewModelScope.launch {
            delay(400)
            val s = _state.value as? MapUiState.Ready ?: return@launch
            if (!RainMapRepository.needsNewGrid(s.map.grid, bounds)) return@launch
            // Area seen recently: reused without a network call.
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

    /** Current loading step, shown on the waiting screen and the pill. */
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
                // First display: the map and the radar appear without waiting for the forecast.
                if (previous == null && radar.isNotEmpty() && !forecast.isCompleted) {
                    _state.value = MapUiState.Ready(place, RainMap(grid, radar), radar.lastIndex, loadedAt = clock(), refreshing = true)
                }
                val predicted = forecast.await()
                val frames = radar + predicted.getOrNull()?.frames.orEmpty()
                if (frames.isEmpty()) throw predicted.exceptionOrNull() ?: IllegalStateException("No rain data")
                val map = RainMap(grid, frames)
                rainMaps.progress.value = LoadProgress(fr.jbrunet.yama.data.repository.LoadStage.PREPARING)
                ForecastBitmaps.prepare(map.frames) // images computed off the main thread
                // Stay on “now” if the user was there, otherwise on the same time.
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
     * Visible area when opening (map centred on the place, zoom [INITIAL_ZOOM]). Tiles scaled to
     * the screen (isTilesScaledToDpi): a 256 px tile is 256 dp, hence a computation in dp.
     */
    private fun initialViewport(place: Place): Bounds? = screenDp?.let { (w, h) ->
        WidgetMapRenderer.bounds(place.latitude, place.longitude, w, h, INITIAL_ZOOM)
    }

    /** Reloads if the data is older than [REFRESH_MS] (radar updated every 10 min). */
    fun refreshIfStale() {
        val s = _state.value
        // Incomplete forecast (Open-Meteo rate limit): retry from the next minute.
        val maxAge = if (s is MapUiState.Ready && s.partial) 60_000L else REFRESH_MS
        if (s is MapUiState.Ready && !s.refreshing && clock() - s.loadedAt >= maxAge) load()
        if (s is MapUiState.Error) load()
    }

    private val _wind = MutableStateFlow(WindUiState())
    val wind: StateFlow<WindUiState> = _wind

    /** Shows / hides the wind; downloads it (one Open-Meteo call) if missing or older than 30 min. */
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
                // Between two forecasts on the same grid: intermediate frames (rain moved).
                val tweenable = from is MapFrame.Forecast && to is MapFrame.Forecast && from.grid == to.grid && nextIndex != 0
                // Between two radar frames: cross-fade, drawn directly by the map.
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
                                    .also { ForecastBitmaps.get(it) } // image ready before display
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

        /** How long a 15 min step is shown while playing, split into [STEPS] frames. */
        const val FRAME_MS = 700L
        const val STEPS = 6

        /** Zoom when the map opens. */
        const val INITIAL_ZOOM = 8

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MeteoApp
                MapViewModel(app.forecastRepository, app.rainMapRepository)
            }
        }
    }
}
