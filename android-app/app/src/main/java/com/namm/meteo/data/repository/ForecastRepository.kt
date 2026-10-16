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

    private fun ForecastDto.toDomain(): Forecast {
        val c = requireNotNull(current) { "Réponse sans données actuelles" }
        val steps = minutely15?.let { m ->
            m.time.zip(m.precipitation).take(8).map { (t, p) -> PrecipitationStep(t, p ?: 0.0) }
        }.orEmpty()
        val days = daily?.let { d ->
            d.time.indices.map { i ->
                DailyForecast(d.time[i], d.tempMin[i], d.tempMax[i], d.precipitationSum[i], d.weatherCode[i])
            }
        }.orEmpty()
        return Forecast(
            current = CurrentWeather(c.temperature, c.precipitation, c.weatherCode, c.windSpeed),
            nextPrecipitation = steps,
            days = days,
        )
    }
}
