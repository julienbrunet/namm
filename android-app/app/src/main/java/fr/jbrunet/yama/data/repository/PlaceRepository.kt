package fr.jbrunet.yama.data.repository

import fr.jbrunet.yama.data.remote.GeocodingApi
import fr.jbrunet.yama.data.remote.GeocodingDto
import fr.jbrunet.yama.domain.Place

class PlaceRepository(private val api: GeocodingApi) {

    suspend fun search(query: String): List<Place> =
        if (query.trim().length < 2) emptyList() else api.search(query.trim(), language = java.util.Locale.getDefault().language).toPlaces()
}

internal fun GeocodingDto.toPlaces(): List<Place> = results.map {
    Place(it.name, it.latitude, it.longitude, listOfNotNull(it.admin1, it.country).joinToString(", ").ifEmpty { null })
}
