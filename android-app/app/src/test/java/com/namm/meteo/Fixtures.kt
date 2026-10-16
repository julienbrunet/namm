package com.namm.meteo

import com.namm.meteo.data.remote.ForecastDto
import com.namm.meteo.data.remote.GeocodingDto
import com.namm.meteo.data.remote.GridPointDto
import com.namm.meteo.data.remote.Network
import com.namm.meteo.data.repository.LocalForecast
import com.namm.meteo.data.repository.RainMapRepository
import com.namm.meteo.data.repository.toDomain
import com.namm.meteo.domain.MapFrame
import com.namm.meteo.domain.Place
import com.namm.meteo.domain.RainMap
import kotlinx.serialization.builtins.ListSerializer
import kotlin.math.exp

/**
 * Données de test : forecast.json et geocoding.json sont de vraies réponses Open-Meteo ;
 * grid.json suit le format multi-points documenté (généré, quota sandbox atteint).
 */
object Fixtures {
    private fun read(name: String) = requireNotNull(javaClass.classLoader?.getResource(name)).readText()

    fun forecastDto(): ForecastDto = Network.json.decodeFromString(ForecastDto.serializer(), read("forecast.json"))
    fun geocodingDto(): GeocodingDto = Network.json.decodeFromString(GeocodingDto.serializer(), read("geocoding.json"))
    fun gridDto(): List<GridPointDto> =
        Network.json.decodeFromString(ListSerializer(GridPointDto.serializer()), read("grid.json"))

    val paris = Place("Paris", 48.8566, 2.3522, "Position actuelle")

    fun localForecast(offline: Boolean = false) = LocalForecast(paris, forecastDto().toDomain(), 0L, offline)

    /** Carte de pluie synthétique : une cellule pluvieuse qui traverse la grille. */
    fun rainMap(): RainMap {
        val grid = RainMapRepository.gridAround(paris)
        val now = 1_792_180_800L
        val radar = (6 downTo 1).map { MapFrame.Radar(now - it * 600, "https://example.invalid/{z}/{x}/{y}.png") }
        val forecast = (0 until 24).map { step ->
            MapFrame.Forecast(
                now + step * 900,
                List(grid.rows) { r ->
                    List(grid.cols) { c ->
                        val dr = r - 4.0 - step * 0.1
                        val dc = c - 2.0 - step * 0.25
                        2.5 * exp(-(dr * dr + dc * dc) / 6)
                    }
                },
            )
        }
        return RainMap(grid, radar + forecast)
    }
}
