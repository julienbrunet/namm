package com.namm.meteo.ui.forecast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.namm.meteo.MeteoApp
import com.namm.meteo.data.repository.ForecastRepository
import com.namm.meteo.domain.Forecast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class City(val name: String, val latitude: Double, val longitude: Double)

sealed interface ForecastUiState {
    data object Loading : ForecastUiState
    data class Success(val city: String, val forecast: Forecast) : ForecastUiState
    data class Error(val message: String) : ForecastUiState
}

class ForecastViewModel(private val repository: ForecastRepository) : ViewModel() {

    private val _state = MutableStateFlow<ForecastUiState>(ForecastUiState.Loading)
    val state: StateFlow<ForecastUiState> = _state

    // Position par défaut en attendant la géolocalisation (voir feuille de route).
    private var city = City("Paris", 48.8566, 2.3522)

    init {
        retry()
    }

    fun retry() = load(city)

    fun load(city: City) {
        this.city = city
        _state.value = ForecastUiState.Loading
        viewModelScope.launch {
            _state.value = runCatching { repository.getForecast(city.latitude, city.longitude) }
                .fold(
                    onSuccess = { ForecastUiState.Success(city.name, it) },
                    onFailure = { ForecastUiState.Error(it.message ?: "Erreur réseau") },
                )
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                ForecastViewModel((this[APPLICATION_KEY] as MeteoApp).forecastRepository)
            }
        }
    }
}
