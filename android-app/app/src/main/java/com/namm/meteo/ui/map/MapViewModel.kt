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
    data class Ready(val place: Place, val map: RainMap, val index: Int, val playing: Boolean = false) : MapUiState
    data class Error(val message: String) : MapUiState
}

class MapViewModel(
    private val forecasts: ForecastRepository,
    private val rainMaps: RainMapRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<MapUiState>(MapUiState.Loading)
    val state: StateFlow<MapUiState> = _state
    private var player: Job? = null

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        _state.value = MapUiState.Loading
        _state.value = runCatching {
            val place = forecasts.cached()?.place ?: forecasts.resolvePlace()
            val map = rainMaps.load(place)
            MapUiState.Ready(place, map, map.nowIndex)
        }.getOrElse { MapUiState.Error(it.message ?: "Erreur réseau") }
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
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MeteoApp
                MapViewModel(app.forecastRepository, app.rainMapRepository)
            }
        }
    }
}
