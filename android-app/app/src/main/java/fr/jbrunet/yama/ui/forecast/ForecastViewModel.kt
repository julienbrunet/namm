package fr.jbrunet.yama.ui.forecast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.jbrunet.yama.MeteoApp
import fr.jbrunet.yama.data.local.SettingsStore
import fr.jbrunet.yama.data.repository.ForecastRepository
import fr.jbrunet.yama.data.repository.LocalForecast
import fr.jbrunet.yama.data.repository.staleReason
import fr.jbrunet.yama.data.update.UpdateController
import fr.jbrunet.yama.data.update.UpdateUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface ForecastUiState {
    data object Loading : ForecastUiState
    data class Success(val data: LocalForecast) : ForecastUiState
    data class Error(val reason: fr.jbrunet.yama.data.repository.StaleReason) : ForecastUiState
}


class ForecastViewModel(
    private val repository: ForecastRepository,
    private val settings: SettingsStore,
    private val updates: UpdateController,
    private val onRefreshed: suspend () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow<ForecastUiState>(
        repository.cached()?.let { ForecastUiState.Success(it) } ?: ForecastUiState.Loading,
    )
    val state: StateFlow<ForecastUiState> = _state

    /** Vrai pendant un rafraîchissement déclenché en tirant l'écran vers le bas. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    val update: StateFlow<UpdateUiState> = updates.state

    init {
        refresh()
        // Recharge dès que l'utilisateur change de ville ou active la position.
        viewModelScope.launch {
            settings.settings.map { it.place to it.useLocation }.distinctUntilChanged().drop(1).collect { refresh() }
        }
        updates.checkIfDue()
    }

    fun refresh() {
        if (_state.value is ForecastUiState.Error) _state.value = ForecastUiState.Loading
        _refreshing.value = _state.value is ForecastUiState.Success
        viewModelScope.launch {
            _state.value = runCatching { repository.load() }.fold(
                onSuccess = { ForecastUiState.Success(it) },
                onFailure = { (_state.value as? ForecastUiState.Success) ?: ForecastUiState.Error(it.staleReason()) },
            )
            _refreshing.value = false
            scheduleRetry()
            // Les widgets reflètent tout de suite la nouvelle prévision.
            (_state.value as? ForecastUiState.Success)?.takeIf { it.data.staleReason == null }?.let { onRefreshed() }
        }
    }

    private var retryJob: kotlinx.coroutines.Job? = null

    /** Données en cache affichées : nouvel essai automatique (après la fin de la saturation si connue). */
    private fun scheduleRetry() {
        retryJob?.cancel()
        val data = (_state.value as? ForecastUiState.Success)?.data ?: return
        if (data.staleReason == null) return
        retryJob = viewModelScope.launch {
            val wait = (fr.jbrunet.yama.data.remote.OpenMeteoQuota.blockedUntilMillis - System.currentTimeMillis())
                .coerceAtLeast(30_000) + 2_000
            kotlinx.coroutines.delay(wait)
            refresh()
        }
    }

    fun onUpdateAction() = updates.onAction()

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MeteoApp
                ForecastViewModel(app.forecastRepository, app.settings, app.updates) { fr.jbrunet.yama.widget.Widgets.updateAll(app) }
            }
        }
    }
}
