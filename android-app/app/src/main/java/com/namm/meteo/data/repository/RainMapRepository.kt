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
import java.util.Locale

/**
 * Carte de pluie : radar observé RainViewer (passé) + grille de prévision Open-Meteo
 * à 15 min autour du lieu (futur). Voir ARCHITECTURE.md.
 */
class RainMapRepository(
    private val openMeteo: OpenMeteoApi,
    private val rainViewer: RainViewerApi,
) {

    suspend fun load(
        center: Place,
        grid: ForecastGrid = gridAround(center),
        now: Long = System.currentTimeMillis() / 1000,
    ): RainMap = coroutineScope {
        val radar = async { runCatching { rainViewer.maps().toFrames() }.getOrDefault(emptyList()) }
        val forecast = async { runCatching { forecastFor(grid, now) } }
        // Radar et prévision sont indépendants : l'un peut échouer sans masquer l'autre.
        val frames = radar.await() + forecast.await().getOrNull().orEmpty()
        if (frames.isEmpty()) throw forecast.await().exceptionOrNull() ?: IllegalStateException("Aucune donnée de pluie")
        RainMap(grid, frames)
    }

    /** Zones de prévision déjà téléchargées (les plus récentes en tête). */
    private val cache = ArrayDeque<CachedGrid>()

    private class CachedGrid(val grid: ForecastGrid, val frames: List<MapFrame>, val at: Long)

    /**
     * Prévision déjà en mémoire (< [CACHE_SECONDS]) dont la grille convient à cette vue :
     * revenir sur une zone déjà vue ne coûte aucun appel Open-Meteo.
     */
    fun cachedFor(view: Bounds, now: Long = System.currentTimeMillis() / 1000): Pair<ForecastGrid, List<MapFrame>>? =
        synchronized(cache) {
            cache.removeAll { now - it.at > CACHE_SECONDS }
            cache.firstOrNull { !needsNewGrid(it.grid, view) }
                ?.let { c -> c.grid to c.frames.filter { it.time + 15 * 60 > now } }
                ?.takeIf { it.second.isNotEmpty() }
        }

    /** Prévision seule pour une grille donnée (zone visible de la carte), mise en cache. */
    suspend fun forecastFor(grid: ForecastGrid, now: Long = System.currentTimeMillis() / 1000): List<MapFrame> {
        val points = grid.coordinates()
        return forecastFrames(
            grid,
            openMeteo.grid(
                latitudes = points.joinToString(",") { "%.4f".format(Locale.ROOT, it.first) },
                longitudes = points.joinToString(",") { "%.4f".format(Locale.ROOT, it.second) },
            ),
            now,
        ).also { frames ->
            if (frames.isNotEmpty()) synchronized(cache) {
                cache.addFirst(CachedGrid(grid, frames, now))
                while (cache.size > CACHE_SIZE) cache.removeLast()
            }
        }
    }

    companion object {
        // 12 × 16 points (format portrait) par appel Open-Meteo ; chaque point compte comme un
        // appel dans le quota gratuit, d'où une grille modeste recalculée seulement si besoin.
        const val CACHE_SECONDS = 15 * 60L
        const val CACHE_SIZE = 10
        const val GRID_COLS = 12
        const val GRID_ROWS = 16
        const val LAT_STEP = 0.1
        const val LON_STEP = 0.145

        fun gridAround(center: Place) = ForecastGrid(
            south = center.latitude - LAT_STEP * (GRID_ROWS - 1) / 2,
            west = center.longitude - LON_STEP * (GRID_COLS - 1) / 2,
            latStep = LAT_STEP,
            lonStep = LON_STEP,
            rows = GRID_ROWS,
            cols = GRID_COLS,
        )

        /** Grille couvrant la zone visible (+10 % de marge), même nombre de points quel que soit le zoom. */
        fun gridFor(view: Bounds): ForecastGrid {
            val padLat = (view.north - view.south) * 0.1
            val padLon = (view.east - view.west) * 0.1
            val south = (view.south - padLat).coerceAtLeast(-80.0)
            val north = (view.north + padLat).coerceAtMost(80.0)
            val west = view.west - padLon
            val east = view.east + padLon
            return ForecastGrid(
                south = south,
                west = west,
                latStep = (north - south) / (GRID_ROWS - 1),
                lonStep = (east - west) / (GRID_COLS - 1),
                rows = GRID_ROWS,
                cols = GRID_COLS,
            )
        }

        /**
         * Faut-il recharger la prévision pour cette vue ? Oui si la grille actuelle ne couvre
         * plus la zone visible, ou si sa maille est 2× trop grossière / trop fine pour le zoom.
         */
        fun needsNewGrid(current: ForecastGrid, view: Bounds): Boolean {
            val north = current.south + current.latStep * (current.rows - 1)
            val east = current.west + current.lonStep * (current.cols - 1)
            val covered = view.south >= current.south && view.north <= north && view.west >= current.west && view.east <= east
            val ideal = (view.north - view.south) * 1.2 / (GRID_ROWS - 1)
            val ratio = current.latStep / ideal
            return !covered || ratio > 2 || ratio < 0.5
        }
    }
}

/** Emprise géographique (degrés). */
data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double)

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
