package com.namm.meteo.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.namm.meteo.MeteoApp
import com.namm.meteo.data.repository.ForecastRepository
import com.namm.meteo.data.repository.RainMapRepository
import com.namm.meteo.domain.Place
import com.namm.meteo.domain.RainMap
import kotlinx.coroutines.Job
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

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        val previous = _state.value as? MapUiState.Ready
        if (previous == null) _state.value = MapUiState.Loading else _state.value = previous.copy(refreshing = true)
        _state.value = runCatching {
            val place = forecasts.cached()?.place ?: forecasts.resolvePlace()
            val map = rainMaps.load(place)
            // On reste sur « maintenant » si l'utilisateur y était, sinon sur la même heure.
            val index = when {
                previous == null || previous.index == previous.map.nowIndex -> map.nowIndex
                else -> map.frames.indexOfFirst { it.time >= previous.map.frames[previous.index].time }
                    .takeIf { it >= 0 } ?: map.nowIndex
            }
            MapUiState.Ready(place, map, index, playing = previous?.playing ?: false, loadedAt = clock())
        }.getOrElse { previous?.copy(refreshing = false) ?: MapUiState.Error(it.message ?: "Erreur réseau") }
    }

    /** Recharge si les données ont plus de [REFRESH_MS] (radar mis à jour toutes les 10 min). */
    fun refreshIfStale() {
        val s = _state.value
        if (s is MapUiState.Ready && !s.refreshing && clock() - s.loadedAt >= REFRESH_MS) load()
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

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MeteoApp
                MapViewModel(app.forecastRepository, app.rainMapRepository)
            }
        }
    }
}
