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
 * Données de test : forecast.json et geocoding.json sont de vraies réponses Open-Meteo
 * (le bloc « hourly » de forecast.json a été ajouté, cohérent avec les min/max réels) ;
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
        val forecast = (0 until 96).map { step ->
            MapFrame.Forecast(
                grid = grid,
                time = now + step * 900,
                values = List(grid.rows) { r ->
                    List(grid.cols) { c ->
                        val dr = r - 9.0 - step * 0.04
                        val dc = c - 4.0 - step * 0.08
                        // Averse principale + un point pluvieux isolé (doit donner une tache ronde).
                        4.0 * exp(-(dr * dr + 2 * dc * dc) / 10) + if (r == 3 && c == 9) 0.6 else 0.0
                    }
                },
            )
        }
        return RainMap(grid, radar + forecast)
    }
}
