package fr.jbrunet.yama.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import fr.jbrunet.yama.data.remote.Network
import fr.jbrunet.yama.domain.Place
import fr.jbrunet.yama.ui.theme.ThemeMode
import fr.jbrunet.yama.ui.theme.ThemeStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

data class Settings(
    /** By default the home screen shows the current position (GPS). */
    val useLocation: Boolean = true,
    /** Saved place shown when [useLocation] is false (and fallback when the position is unavailable). */
    val place: Place = Place.DEFAULT,
    /** Places saved by the user. */
    val savedPlaces: List<Place> = emptyList(),
    val rainAlerts: Boolean = true,
    val autoUpdate: Boolean = true,
    /** Météo-France API key (AROME-PI); empty = Open-Meteo only. */
    val aromeApiKey: String = "",
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val style: ThemeStyle = ThemeStyle.DEFAULT,
)

private val Context.dataStore by preferencesDataStore("settings")

/** Météo-France key, kept apart: this file is excluded from Android backups (backup_rules.xml, data_extraction_rules.xml). */
private val Context.secretStore by preferencesDataStore("secrets")
private val placeList = ListSerializer(Place.serializer())

class SettingsStore(private val context: Context) {

    private object Keys {
        val place = stringPreferencesKey("place")
        val savedPlaces = stringPreferencesKey("saved_places")
        val useLocation = booleanPreferencesKey("use_location")
        val rainAlerts = booleanPreferencesKey("rain_alerts")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val aromeApiKey = stringPreferencesKey("arome_api_key")
        val theme = stringPreferencesKey("theme")
        val style = stringPreferencesKey("theme_style")
    }

    val settings: Flow<Settings> = combine(context.dataStore.data, context.secretStore.data) { p, secrets ->
        Settings(
            useLocation = p[Keys.useLocation] ?: true,
            place = p[Keys.place]?.let { decode { Network.json.decodeFromString(Place.serializer(), it) } } ?: Place.DEFAULT,
            savedPlaces = p[Keys.savedPlaces]?.let { decode { Network.json.decodeFromString(placeList, it) } }.orEmpty(),
            rainAlerts = p[Keys.rainAlerts] ?: true,
            autoUpdate = p[Keys.autoUpdate] ?: true,
            aromeApiKey = secrets[Keys.aromeApiKey] ?: "",
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            style = p[Keys.style]?.let { runCatching { ThemeStyle.valueOf(it) }.getOrNull() } ?: ThemeStyle.DEFAULT,
        )
    }

    suspend fun current(): Settings = settings.first()

    /** Shows the current position on the home screen. */
    suspend fun selectCurrentLocation() = context.dataStore.edit { it[Keys.useLocation] = true }

    /** Shows a saved place (adds it to the list if missing). */
    suspend fun selectPlace(place: Place) = context.dataStore.edit {
        val saved = it[Keys.savedPlaces]?.let { s -> decode { Network.json.decodeFromString(placeList, s) } }.orEmpty()
        if (saved.none { p -> p.sameAs(place) }) {
            it[Keys.savedPlaces] = Network.json.encodeToString(placeList, saved + place)
        }
        it[Keys.place] = Network.json.encodeToString(Place.serializer(), place)
        it[Keys.useLocation] = false
    }

    suspend fun removePlace(place: Place) = context.dataStore.edit {
        val saved = it[Keys.savedPlaces]?.let { s -> decode { Network.json.decodeFromString(placeList, s) } }.orEmpty()
        it[Keys.savedPlaces] = Network.json.encodeToString(placeList, saved.filterNot { p -> p.sameAs(place) })
        val active = it[Keys.place]?.let { s -> decode { Network.json.decodeFromString(Place.serializer(), s) } }
        if (active != null && active.sameAs(place)) it[Keys.useLocation] = true
    }

    suspend fun setRainAlerts(value: Boolean) = context.dataStore.edit { it[Keys.rainAlerts] = value }
    suspend fun setAromeApiKey(value: String) {
        context.secretStore.edit { it[Keys.aromeApiKey] = value.filterNot { c -> c.isWhitespace() } }
    }

    suspend fun setTheme(value: ThemeMode) = context.dataStore.edit { it[Keys.theme] = value.name }
    suspend fun setStyle(value: ThemeStyle) = context.dataStore.edit { it[Keys.style] = value.name }
    suspend fun setAutoUpdate(value: Boolean) = context.dataStore.edit { it[Keys.autoUpdate] = value }

    private inline fun <T> decode(block: () -> T): T? = runCatching(block).getOrNull()
}
