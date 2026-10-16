package com.namm.meteo.data.repository

import com.namm.meteo.data.remote.GridPointDto
import com.namm.meteo.data.remote.OpenMeteoApi
import com.namm.meteo.data.remote.RainViewerApi
import com.namm.meteo.data.remote.RainViewerMapsDto
import com.namm.meteo.domain.ForecastGrid
import com.namm.meteo.domain.MapFrame
import com.namm.meteo.domain.Place
import com.namm.meteo.domain.RainMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Carte de pluie : radar observé RainViewer (passé) + grille de prévision Open-Meteo
 * à 15 min autour du lieu (futur). Voir ARCHITECTURE.md.
 */
class RainMapRepository(
    private val openMeteo: OpenMeteoApi,
    private val rainViewer: RainViewerApi,
) {

    suspend fun load(center: Place, now: Long = System.currentTimeMillis() / 1000): RainMap = coroutineScope {
        val grid = gridAround(center)
        val radar = async { runCatching { rainViewer.maps().toFrames() }.getOrDefault(emptyList()) }
        val points = async {
            runCatching {
                openMeteo.grid(
                latitudes = grid.coordinates().joinToString(",") { "%.4f".format(java.util.Locale.ROOT, it.first) },
                    longitudes = grid.coordinates().joinToString(",") { "%.4f".format(java.util.Locale.ROOT, it.second) },
                )
            }
        }
        // Radar et prévision sont indépendants : l'un peut échouer sans masquer l'autre.
        val forecast = points.await()
        val frames = radar.await() + forecast.getOrNull().orEmpty().let { forecastFrames(grid, it, now) }
        if (frames.isEmpty()) throw forecast.exceptionOrNull() ?: IllegalStateException("Aucune donnée de pluie")
        RainMap(grid, frames)
    }

    companion object {
        const val GRID_SIZE = 11
        const val LAT_STEP = 0.045 // ≈ 5 km
        const val LON_STEP = 0.065 // ≈ 5 km en France

        fun gridAround(center: Place) = ForecastGrid(
            south = center.latitude - LAT_STEP * (GRID_SIZE - 1) / 2,
            west = center.longitude - LON_STEP * (GRID_SIZE - 1) / 2,
            latStep = LAT_STEP,
            lonStep = LON_STEP,
            rows = GRID_SIZE,
            cols = GRID_SIZE,
        )
    }
}

/** Coordonnées des points, ligne par ligne en partant du sud-ouest. */
internal fun ForecastGrid.coordinates(): List<Pair<Double, Double>> =
    (0 until rows).flatMap { r -> (0 until cols).map { c -> (south + r * latStep) to (west + c * lonStep) } }

internal fun RainViewerMapsDto.toFrames(): List<MapFrame> = radar.past.map {
    MapFrame.Radar(it.time, "$host${it.path}/256/{z}/{x}/{y}/2/1_1.png")
}

/** Une image par pas de 15 min, à partir du quart d'heure en cours. */
internal fun forecastFrames(grid: ForecastGrid, points: List<GridPointDto>, now: Long): List<MapFrame> {
    if (points.size != grid.rows * grid.cols) return emptyList()
    val times = points.first().minutely15.time
    return times.indices
        .filter { times[it] + 15 * 60 > now }
        .map { i ->
            MapFrame.Forecast(
                time = times[i],
                values = (0 until grid.rows).map { r ->
                    (0 until grid.cols).map { c ->
                        points[r * grid.cols + c].minutely15.precipitation.getOrNull(i) ?: 0.0
                    }
                },
            )
        }
}
