package com.namm.meteo.data.repository

import com.namm.meteo.data.remote.GeocodingApi
import com.namm.meteo.data.remote.GeocodingDto
import com.namm.meteo.domain.Place

class PlaceRepository(private val api: GeocodingApi) {

    suspend fun search(query: String): List<Place> =
        if (query.trim().length < 2) emptyList() else api.search(query.trim()).toPlaces()
}

internal fun GeocodingDto.toPlaces(): List<Place> = results.map {
    Place(it.name, it.latitude, it.longitude, listOfNotNull(it.admin1, it.country).joinToString(", ").ifEmpty { null })
}
