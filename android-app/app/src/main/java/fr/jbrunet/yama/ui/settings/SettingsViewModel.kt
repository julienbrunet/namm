package fr.jbrunet.yama.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.jbrunet.yama.BuildConfig
import fr.jbrunet.yama.MeteoApp
import fr.jbrunet.yama.R
import fr.jbrunet.yama.data.arome.AromePiSource
import fr.jbrunet.yama.data.arome.InvalidKeyException
import fr.jbrunet.yama.data.local.Settings
import fr.jbrunet.yama.data.local.SettingsStore
import fr.jbrunet.yama.data.location.LocationProvider
import fr.jbrunet.yama.data.repository.PlaceRepository
import fr.jbrunet.yama.data.update.UpdateController
import fr.jbrunet.yama.domain.Place
import fr.jbrunet.yama.ui.UiText
import fr.jbrunet.yama.ui.theme.ThemeMode
import fr.jbrunet.yama.ui.theme.ThemeStyle
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
    val error: UiText? = null,
)

class SettingsViewModel(
    private val store: SettingsStore,
    private val places: PlaceRepository,
    private val updates: UpdateController,
    private val location: LocationProvider,
    private val arome: AromePiSource,
    private val onThemeChanged: () -> Unit = {},
) : ViewModel() {

    private val _aromeStatus = MutableStateFlow<UiText?>(null)
    val aromeStatus: StateFlow<UiText?> = _aromeStatus

    /** Vérifie la clé auprès de Météo-France puis l'enregistre si elle fonctionne. */
    fun testAndSaveAromeKey(key: String) = viewModelScope.launch {
        if (key.isBlank()) {
            store.setAromeApiKey("")
            _aromeStatus.value = UiText(R.string.arome_key_removed)
            return@launch
        }
        _aromeStatus.value = UiText(R.string.checking)
        _aromeStatus.value = runCatching { arome.latestLayer(AromePiSource.normalizeKey(key), fresh = true) }.fold(
            onSuccess = {
                store.setAromeApiKey(key)
                val run = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")
                    .withZone(java.time.ZoneId.systemDefault()).format(it.run)
                UiText(R.string.arome_key_valid, run, it.layer.lowercase().replace('_', ' '))
            },
            onFailure = {
                val type = when (AromePiSource.credentialType(key.trim())) {
                    AromePiSource.Companion.CredentialType.API_KEY -> R.string.credential_api_key
                    AromePiSource.Companion.CredentialType.JWT -> R.string.credential_jwt
                    AromePiSource.Companion.CredentialType.APPLICATION_ID -> R.string.credential_application_id
                }
                if (it is InvalidKeyException) {
                    UiText(R.string.arome_key_refused, UiText(type), AromePiSource.normalizeKey(key).length, it.message.orEmpty())
                } else {
                    UiText(R.string.arome_check_failed, it.message.orEmpty())
                }
            },
        )
    }

    fun removeAromeKey() = testAndSaveAromeKey("")

    fun hasLocationPermission() = location.hasPermission()

    val settings: StateFlow<Settings> = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search

    private val _updateStatus = MutableStateFlow<UiText?>(null)
    val updateStatus: StateFlow<UiText?> = _updateStatus

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
                onSuccess = { SearchState(query, it, error = if (it.isEmpty()) UiText(R.string.no_city_found) else null) },
                onFailure = { SearchState(query, error = UiText(R.string.search_failed)) },
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
    fun setTheme(value: ThemeMode) = viewModelScope.launch {
        store.setTheme(value)
        onThemeChanged()
    }

    fun setStyle(value: ThemeStyle) = viewModelScope.launch {
        store.setStyle(value)
        onThemeChanged()
    }
    fun setAutoUpdate(value: Boolean) = viewModelScope.launch { store.setAutoUpdate(value) }

    fun checkUpdate() = viewModelScope.launch {
        _updateStatus.value = UiText(R.string.update_searching)
        _updateStatus.value = runCatching { updates.check() }.fold(
            onSuccess = {
                if (it == null) UiText(R.string.update_latest)
                else UiText(R.string.update_found, it.version.toString())
            },
            onFailure = { UiText(R.string.update_check_failed) },
        )
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MeteoApp
                SettingsViewModel(app.settings, app.placeRepository, app.updates, app.location, app.aromePi) { fr.jbrunet.yama.widget.Widgets.refreshNow(app) }
            }
        }
    }
}
