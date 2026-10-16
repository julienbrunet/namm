package com.namm.meteo.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.namm.meteo.data.remote.Network
import com.namm.meteo.domain.Place
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

data class Settings(
    /** Par défaut l'accueil affiche la position actuelle (GPS). */
    val useLocation: Boolean = true,
    /** Lieu enregistré affiché quand [useLocation] est faux (et repli si la position est indisponible). */
    val place: Place = Place.DEFAULT,
    /** Lieux préenregistrés par l'utilisateur. */
    val savedPlaces: List<Place> = emptyList(),
    val rainAlerts: Boolean = true,
    val autoUpdate: Boolean = true,
)

private val Context.dataStore by preferencesDataStore("settings")
private val placeList = ListSerializer(Place.serializer())

class SettingsStore(private val context: Context) {

    private object Keys {
        val place = stringPreferencesKey("place")
        val savedPlaces = stringPreferencesKey("saved_places")
        val useLocation = booleanPreferencesKey("use_location")
        val rainAlerts = booleanPreferencesKey("rain_alerts")
        val autoUpdate = booleanPreferencesKey("auto_update")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            useLocation = p[Keys.useLocation] ?: true,
            place = p[Keys.place]?.let { decode { Network.json.decodeFromString(Place.serializer(), it) } } ?: Place.DEFAULT,
            savedPlaces = p[Keys.savedPlaces]?.let { decode { Network.json.decodeFromString(placeList, it) } }.orEmpty(),
            rainAlerts = p[Keys.rainAlerts] ?: true,
            autoUpdate = p[Keys.autoUpdate] ?: true,
        )
    }

    suspend fun current(): Settings = settings.first()

    /** Affiche la position actuelle sur l'accueil. */
    suspend fun selectCurrentLocation() = context.dataStore.edit { it[Keys.useLocation] = true }

    /** Affiche un lieu enregistré (l'ajoute à la liste s'il n'y est pas). */
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
    suspend fun setAutoUpdate(value: Boolean) = context.dataStore.edit { it[Keys.autoUpdate] = value }

    private inline fun <T> decode(block: () -> T): T? = runCatching(block).getOrNull()
}
