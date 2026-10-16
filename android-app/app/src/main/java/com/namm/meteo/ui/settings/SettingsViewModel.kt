package com.namm.meteo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.namm.meteo.BuildConfig
import com.namm.meteo.MeteoApp
import com.namm.meteo.data.arome.AromePiSource
import com.namm.meteo.data.arome.InvalidKeyException
import com.namm.meteo.data.local.Settings
import com.namm.meteo.data.local.SettingsStore
import com.namm.meteo.data.location.LocationProvider
import com.namm.meteo.data.repository.PlaceRepository
import com.namm.meteo.data.update.UpdateManager
import com.namm.meteo.domain.Place
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class SettingsViewModel(
    private val store: SettingsStore,
    private val places: PlaceRepository,
    private val updates: UpdateManager,
    private val location: LocationProvider,
    private val arome: AromePiSource,
) : ViewModel() {

    private val _aromeStatus = MutableStateFlow<String?>(null)
    val aromeStatus: StateFlow<String?> = _aromeStatus

    /** Vérifie la clé auprès de Météo-France puis l'enregistre si elle fonctionne. */
    fun testAndSaveAromeKey(key: String) = viewModelScope.launch {
        if (key.isBlank()) {
            store.setAromeApiKey("")
            _aromeStatus.value = "Clé retirée : prévision Open-Meteo"
            return@launch
        }
        _aromeStatus.value = "Vérification…"
        _aromeStatus.value = runCatching { arome.latestLayer(key.trim(), fresh = true) }.fold(
            onSuccess = {
                store.setAromeApiKey(key)
                val run = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")
                    .withZone(java.time.ZoneId.systemDefault()).format(it.run)
                val warning = ""
                "Clé valide ✓ · run du $run · couche ${it.layer.lowercase().replace('_', ' ')}$warning"
            },
            onFailure = {
                val type = when (AromePiSource.credentialType(key.trim())) {
                    AromePiSource.Companion.CredentialType.API_KEY -> "API Key"
                    AromePiSource.Companion.CredentialType.JWT -> "API Key ou jeton"
                    AromePiSource.Companion.CredentialType.APPLICATION_ID -> "Application ID"
                }
                if (it is InvalidKeyException) "Identifiant reconnu comme $type, mais refusé : ${it.message}. " +
                    "Vérifiez l'abonnement de l'application à AROME-PI sur le portail."
                else "Vérification impossible : ${it.message}"
            },
        )
    }

    fun removeAromeKey() = testAndSaveAromeKey("")

    fun hasLocationPermission() = location.hasPermission()

    val settings: StateFlow<Settings> = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search

    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus

    val versionName: String = BuildConfig.VERSION_NAME

    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _search.value = _search.value.copy(query = query, error = null)
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _search.value = SearchState(query)
            return
        }
        searchJob = viewModelScope.launch {
            delay(350) // anti-rebond pendant la frappe
            _search.value = _search.value.copy(loading = true)
            _search.value = runCatching { places.search(query) }.fold(
                onSuccess = { SearchState(query, it, error = if (it.isEmpty()) "Aucune ville trouvée" else null) },
                onFailure = { SearchState(query, error = "Recherche impossible (réseau)") },
            )
        }
    }

    /** Ajoute (si besoin) et affiche ce lieu sur l'accueil. */
    fun choose(place: Place) = viewModelScope.launch {
        store.selectPlace(place)
        _search.value = SearchState()
    }

    fun chooseCurrentLocation() = viewModelScope.launch { store.selectCurrentLocation() }
    fun remove(place: Place) = viewModelScope.launch { store.removePlace(place) }
    fun setRainAlerts(value: Boolean) = viewModelScope.launch { store.setRainAlerts(value) }
    fun setAutoUpdate(value: Boolean) = viewModelScope.launch { store.setAutoUpdate(value) }

    fun checkUpdate() = viewModelScope.launch {
        _updateStatus.value = "Recherche…"
        _updateStatus.value = runCatching { updates.check() }.fold(
            onSuccess = { if (it == null) "Vous avez la dernière version" else "Version ${it.version} disponible : revenez à l'accueil" },
            onFailure = { "Vérification impossible (réseau)" },
        )
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MeteoApp
                SettingsViewModel(app.settings, app.placeRepository, app.updateManager, app.location, app.aromePi)
            }
        }
    }
}
