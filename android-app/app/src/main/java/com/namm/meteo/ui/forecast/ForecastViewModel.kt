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

sealed interface ForecastUiState {
    data object Loading : ForecastUiState
    data class Success(val forecast: Forecast) : ForecastUiState
    data class Error(val message: String) : ForecastUiState
}

class ForecastViewModel(private val repository: ForecastRepository) : ViewModel() {

    private val _state = MutableStateFlow<ForecastUiState>(ForecastUiState.Loading)
    val state: StateFlow<ForecastUiState> = _state

    init {
        // Position par défaut (Paris) en attendant la géolocalisation.
        refresh(48.8566, 2.3522)
    }

    fun refresh(latitude: Double, longitude: Double) {
        _state.value = ForecastUiState.Loading
        viewModelScope.launch {
            _state.value = runCatching { repository.getForecast(latitude, longitude) }
                .fold(
                    onSuccess = { ForecastUiState.Success(it) },
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
