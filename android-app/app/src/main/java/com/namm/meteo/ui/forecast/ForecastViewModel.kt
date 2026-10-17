package com.namm.meteo.ui.forecast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.namm.meteo.MeteoApp
import com.namm.meteo.data.local.SettingsStore
import com.namm.meteo.data.repository.ForecastRepository
import com.namm.meteo.data.repository.LocalForecast
import com.namm.meteo.data.update.AvailableUpdate
import com.namm.meteo.data.update.UpdateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

sealed interface ForecastUiState {
    data object Loading : ForecastUiState
    data class Success(val data: LocalForecast) : ForecastUiState
    data class Error(val message: String) : ForecastUiState
}

sealed interface UpdateUiState {
    data object None : UpdateUiState
    data class Available(val update: AvailableUpdate) : UpdateUiState
    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateUiState
    data class Ready(val update: AvailableUpdate, val apk: File) : UpdateUiState
}

class ForecastViewModel(
    private val repository: ForecastRepository,
    private val settings: SettingsStore,
    private val updates: UpdateManager,
) : ViewModel() {

    private val _state = MutableStateFlow<ForecastUiState>(
        repository.cached()?.let { ForecastUiState.Success(it) } ?: ForecastUiState.Loading,
    )
    val state: StateFlow<ForecastUiState> = _state

    /** Vrai pendant un rafraîchissement déclenché en tirant l'écran vers le bas. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _update = MutableStateFlow<UpdateUiState>(UpdateUiState.None)
    val update: StateFlow<UpdateUiState> = _update

    init {
        refresh()
        // Recharge dès que l'utilisateur change de ville ou active la position.
        viewModelScope.launch {
            settings.settings.map { it.place to it.useLocation }.distinctUntilChanged().drop(1).collect { refresh() }
        }
        checkForUpdate()
    }

    fun refresh() {
        if (_state.value is ForecastUiState.Error) _state.value = ForecastUiState.Loading
        _refreshing.value = _state.value is ForecastUiState.Success
        viewModelScope.launch {
            _state.value = runCatching { repository.load() }.fold(
                onSuccess = { ForecastUiState.Success(it) },
                onFailure = { (_state.value as? ForecastUiState.Success) ?: ForecastUiState.Error(it.message ?: "Erreur réseau") },
            )
            _refreshing.value = false
            scheduleRetry()
        }
    }

    private var retryJob: kotlinx.coroutines.Job? = null

    /** Données en cache affichées : nouvel essai automatique (après la fin de la saturation si connue). */
    private fun scheduleRetry() {
        retryJob?.cancel()
        val data = (_state.value as? ForecastUiState.Success)?.data ?: return
        if (data.staleReason == null) return
        retryJob = viewModelScope.launch {
            val wait = (com.namm.meteo.data.remote.OpenMeteoQuota.blockedUntilMillis - System.currentTimeMillis())
                .coerceAtLeast(30_000) + 2_000
            kotlinx.coroutines.delay(wait)
            refresh()
        }
    }

    /** Vérifie GitHub ; si la mise à jour auto est active, télécharge directement l'APK. */
    private fun checkForUpdate() = viewModelScope.launch {
        val available = runCatching { updates.check() }.getOrNull() ?: return@launch
        _update.value = UpdateUiState.Available(available)
        if (settings.current().autoUpdate) download()
    }

    fun onUpdateAction() {
        when (val u = _update.value) {
            is UpdateUiState.Available -> download()
            is UpdateUiState.Ready -> updates.install(u.apk)
            else -> Unit
        }
    }

    private fun download() {
        val available = (_update.value as? UpdateUiState.Available)?.update ?: return
        _update.value = UpdateUiState.Downloading(available, 0f)
        viewModelScope.launch {
            val apk = updates.download(available) { _update.value = UpdateUiState.Downloading(available, it) }
            _update.value = if (apk != null) UpdateUiState.Ready(available, apk) else UpdateUiState.Available(available)
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MeteoApp
                ForecastViewModel(app.forecastRepository, app.settings, app.updateManager)
            }
        }
    }
}
