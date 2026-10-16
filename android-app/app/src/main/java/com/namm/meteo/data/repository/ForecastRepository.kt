package com.namm.meteo.data.repository

import com.namm.meteo.data.remote.ForecastDto
import com.namm.meteo.data.remote.OpenMeteoApi
import com.namm.meteo.domain.CurrentWeather
import com.namm.meteo.domain.DailyForecast
import com.namm.meteo.domain.Forecast
import com.namm.meteo.domain.PrecipitationStep

class ForecastRepository(private val api: OpenMeteoApi) {

    suspend fun getForecast(latitude: Double, longitude: Double): Forecast =
        api.forecast(latitude, longitude).toDomain()
}

/** Nombre de pas de 15 min affichés (2 h). */
private const val NOWCAST_STEPS = 8

internal fun ForecastDto.toDomain(): Forecast {
    val c = requireNotNull(current) { "Réponse sans données actuelles" }
    // minutely_15 commence à minuit : on ne garde que les pas à partir de l'heure courante.
    val steps = minutely15?.let { m ->
        m.time.zip(m.precipitation)
            .dropWhile { (t, _) -> t < c.time.take(14) + floorQuarter(c.time) }
            .take(NOWCAST_STEPS)
            .map { (t, p) -> PrecipitationStep(t, p ?: 0.0) }
    }.orEmpty()
    val days = daily?.let { d ->
        d.time.indices.map { i ->
            DailyForecast(d.time[i], d.tempMin[i], d.tempMax[i], d.precipitationSum[i], d.weatherCode[i])
        }
    }.orEmpty()
    return Forecast(
        current = CurrentWeather(
            time = c.time,
            temperature = c.temperature,
            apparentTemperature = c.apparentTemperature,
            humidity = c.humidity,
            precipitation = c.precipitation,
            weatherCode = c.weatherCode,
            windSpeed = c.windSpeed,
        ),
        nextPrecipitation = steps,
        days = days,
    )
}

/** "2026-10-16T10:37" -> "30" */
private fun floorQuarter(isoTime: String): String {
    val minutes = isoTime.substringAfterLast(':').toIntOrNull() ?: 0
    return "%02d".format(minutes / 15 * 15)
}
