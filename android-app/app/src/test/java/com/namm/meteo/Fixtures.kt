package com.namm.meteo

import com.namm.meteo.data.remote.ForecastDto
import kotlinx.serialization.json.Json

/** Réponse réelle d'Open-Meteo (Paris) enregistrée dans src/test/resources/forecast.json. */
object Fixtures {
    private val json = Json { ignoreUnknownKeys = true }

    fun forecastDto(): ForecastDto {
        val text = requireNotNull(javaClass.classLoader?.getResource("forecast.json")).readText()
        return json.decodeFromString(ForecastDto.serializer(), text)
    }
}
