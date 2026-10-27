package fr.jbrunet.yama

import fr.jbrunet.yama.data.remote.ForecastDto
import fr.jbrunet.yama.data.remote.GeocodingDto
import fr.jbrunet.yama.data.remote.GridPointDto
import fr.jbrunet.yama.data.remote.Network
import fr.jbrunet.yama.data.repository.LocalForecast
import fr.jbrunet.yama.data.repository.RainMapRepository
import fr.jbrunet.yama.data.repository.toDomain
import fr.jbrunet.yama.domain.MapFrame
import fr.jbrunet.yama.domain.Place
import fr.jbrunet.yama.domain.RainMap
import kotlinx.serialization.builtins.ListSerializer
import kotlin.math.exp

/**
 * Données de test : forecast.json et geocoding.json sont de vraies réponses Open-Meteo
 * (le bloc « hourly » de forecast.json a été ajouté, cohérent avec les min/max réels, ainsi que
 * les heures de lever et de coucher du soleil à Paris) ;
 * grid.json suit le format multi-points documenté (généré, quota sandbox atteint).
 */
object Fixtures {
    private fun read(name: String) = requireNotNull(javaClass.classLoader?.getResource(name)).readText()

    fun forecastDto(): ForecastDto = Network.json.decodeFromString(ForecastDto.serializer(), read("forecast.json"))
    fun geocodingDto(): GeocodingDto = Network.json.decodeFromString(GeocodingDto.serializer(), read("geocoding.json"))
    fun gridDto(): List<GridPointDto> =
        Network.json.decodeFromString(ListSerializer(GridPointDto.serializer()), read("grid.json"))

    val paris = Place("Paris", 48.8566, 2.3522, "Position actuelle")

    fun localForecast(offline: Boolean = false) = LocalForecast(paris, forecastDto().toDomain(), 1_792_178_100_000L, if (offline) fr.jbrunet.yama.data.repository.StaleReason.RATE_LIMITED else null)

    /** Carte de pluie synthétique : une cellule pluvieuse qui traverse la grille. */
    fun rainMap(): RainMap {
        val grid = RainMapRepository.gridAround(paris)
        val now = 1_792_180_800L
        val radar = (6 downTo 1).map { MapFrame.Radar(now - it * 600, "https://example.invalid/{z}/{x}/{y}.png") }
        val forecast = (0 until 96).map { step ->
            MapFrame.Forecast(
                grid = grid,
                time = now + step * 900,
                values = FloatArray(grid.rows * grid.cols) { k ->
                    val r = k / grid.cols
                    val c = k % grid.cols
                    val dr = r - 9.0 - step * 0.04
                    val dc = c - 4.0 - step * 0.08
                    // Averse principale + un point pluvieux isolé (doit donner une tache ronde).
                    (4.0 * exp(-(dr * dr + 2 * dc * dc) / 10) + if (r == 3 && c == 9) 0.6 else 0.0).toFloat()
                },
                // Front nuageux large autour de l'averse, plus une zone dégagée au sud-est.
                clouds = fr.jbrunet.yama.domain.CloudLayer(grid, FloatArray(grid.rows * grid.cols) { k ->
                    val r = k / grid.cols
                    val c = k % grid.cols
                    (100 - 9.0 * kotlin.math.abs(r - 9 - (c - 4) * 0.5)).coerceIn(0.0, 100.0).toFloat()
                }),
            )
        }
        return RainMap(grid, radar + forecast)
    }

    /** Vent synthétique : sud-ouest qui forcit vers le nord de la grille, sur une grille grossière. */
    fun wind(grid: fr.jbrunet.yama.domain.ForecastGrid): List<fr.jbrunet.yama.domain.WindFrame> {
        val g = RainMapRepository.coarse(grid)
        val n = g.rows * g.cols
        return listOf(
            fr.jbrunet.yama.domain.WindFrame(
                time = 1_792_180_800L - 3600,
                grid = g,
                speed = FloatArray(n) { k -> 5f + (k / g.cols) * 9f + (k % g.cols) * 2f },
                direction = FloatArray(n) { k -> 200f + (k % g.cols) * 8f },
                gusts = FloatArray(n) { 0f },
            ),
        )
    }
}
