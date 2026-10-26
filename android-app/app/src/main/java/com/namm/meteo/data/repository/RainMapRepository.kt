package com.namm.meteo.data.repository

import com.namm.meteo.data.arome.AromePiSource
import com.namm.meteo.data.remote.GridPointDto
import com.namm.meteo.data.remote.OpenMeteoQuota
import com.namm.meteo.data.remote.OpenMeteoApi
import com.namm.meteo.data.remote.RainViewerApi
import com.namm.meteo.data.remote.RainViewerMapsDto
import com.namm.meteo.domain.CloudLayer
import com.namm.meteo.domain.ForecastGrid
import com.namm.meteo.domain.MapFrame
import com.namm.meteo.domain.Place
import com.namm.meteo.domain.RainMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/**
 * Carte de pluie : radar observé RainViewer (passé) + grille de prévision Open-Meteo
 * à 15 min autour du lieu (futur). Voir ARCHITECTURE.md.
 */
class RainMapRepository(
    private val openMeteo: OpenMeteoApi,
    private val rainViewer: RainViewerApi,
    private val arome: AromePiSource = AromePiSource(),
    /** Clé Météo-France saisie dans les paramètres (null : Open-Meteo seul). */
    private val aromeKey: suspend () -> String? = { null },
) {

    /** Étape en cours du chargement de la carte (null : rien en cours), affichée à l'écran. */
    val progress = MutableStateFlow<LoadProgress?>(null)

    /** Images radar RainViewer (passé récent), rapides à obtenir. */
    suspend fun radar(): List<MapFrame> = runCatching { rainViewer.maps().toFrames() }.getOrDefault(emptyList())

    suspend fun load(
        center: Place,
        grid: ForecastGrid = gridAround(center),
        now: Long = System.currentTimeMillis() / 1000,
    ): RainMap = withContext(Dispatchers.Default) {
        val radar = async { runCatching { rainViewer.maps().toFrames() }.getOrDefault(emptyList()) }
        val forecast = async { runCatching { forecastFor(grid, now).frames } }
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

    /**
     * Prévision seule pour une grille donnée (zone visible de la carte), mise en cache.
     * Avec une clé Météo-France et une zone en France : AROME-PI pour les 6 premières heures
     * (maille 1,3 km), Open-Meteo ensuite ; sinon (ou en cas d'échec) Open-Meteo seul.
     */
    suspend fun forecastFor(grid: ForecastGrid, now: Long = System.currentTimeMillis() / 1000): ForecastFrames =
        // Décodage GeoTIFF / JSON coûteux : jamais sur le thread principal (gel de l'appli).
        withContext(Dispatchers.Default) {
            val key = aromeKey()?.takeIf { it.isNotBlank() && AromePiSource.covers(grid) }
            progress.value = LoadProgress(if (key != null) "Prévision Météo-France" else "Prévision Open-Meteo")
            val aromeFrames = key?.let {
                async {
                    runCatching {
                        arome.forecast(it, grid, now) { done, total -> progress.value = LoadProgress("Prévision Météo-France", done, total) }
                    }.getOrDefault(emptyList())
                }
            }
            // Avec AROME-PI, Open-Meteo ne sert qu'au-delà de +6 h : une grille grossière suffit
            // (≈ 80 points au lieu de 192) et ménage le quota par minute.
            val openMeteoGrid = if (key != null) coarse(grid) else grid
            val openMeteoFrames = runCatching { openMeteoFrames(openMeteoGrid, now) }
            val precise = aromeFrames?.await().orEmpty()
            val after = precise.lastOrNull()?.time ?: Long.MIN_VALUE
            // AROME-PI ne donne pas les nuages : on reprend ceux d'Open-Meteo pour la même heure.
            val cloudsByHour = openMeteoFrames.getOrNull().orEmpty().filterIsInstance<MapFrame.Forecast>()
                .associate { it.time - Math.floorMod(it.time, 3600L) to it.clouds }
            val withClouds = precise.map { f ->
                if (f is MapFrame.Forecast && f.clouds == null) MapFrame.Forecast(f.time, f.values, f.grid, f.source, cloudsByHour[f.time - Math.floorMod(f.time, 3600L)]) else f
            }
            val frames = withClouds + openMeteoFrames.getOrNull().orEmpty().filter { it.time > after }
            if (frames.isEmpty()) openMeteoFrames.getOrThrow()
            // Prévision incomplète (Open-Meteo saturé) : non mise en cache, et nouvel essai rapide.
            val partial = openMeteoFrames.isFailure
            if (!partial) cacheFrames(grid, frames, now)
            ForecastFrames(frames, partial)
        }

    /**
     * Pluie actuelle sur [grid] pour le widget carte : une seule image AROME-PI si une clé est
     * active (1-2 requêtes), sinon Open-Meteo sur une grille grossière (80 points) si [allowOpenMeteo].
     */
    suspend fun currentFrame(grid: ForecastGrid, allowOpenMeteo: Boolean, now: Long = System.currentTimeMillis() / 1000): MapFrame.Forecast? = withContext(Dispatchers.Default) {
        val key = aromeKey()?.takeIf { it.isNotBlank() && AromePiSource.covers(grid) }
        if (key != null) {
            runCatching { arome.forecast(key, grid, now, maxFrames = 1) }.getOrNull()
                ?.filterIsInstance<MapFrame.Forecast>()?.firstOrNull()?.let { return@withContext it }
        }
        if (!allowOpenMeteo) return@withContext null
        openMeteoFrames(coarse(grid), now).filterIsInstance<MapFrame.Forecast>().firstOrNull()
    }

    private fun cacheFrames(grid: ForecastGrid, frames: List<MapFrame>, now: Long) {
        if (frames.isNotEmpty()) synchronized(cache) {
            cache.addFirst(CachedGrid(grid, frames, now))
            while (cache.size > CACHE_SIZE) cache.removeLast()
        }
    }

    private suspend fun openMeteoFrames(grid: ForecastGrid, now: Long): List<MapFrame> {
        // Open-Meteo saturé : la carte attend, pour laisser le quota à l'accueil.
        if (OpenMeteoQuota.isBlocked) throw IOException("Open-Meteo saturé, nouvel essai dans une minute")
        val points = grid.coordinates()
        return forecastFrames(
            grid,
            openMeteo.grid(
                latitudes = points.joinToString(",") { "%.4f".format(Locale.ROOT, it.first) },
                longitudes = points.joinToString(",") { "%.4f".format(Locale.ROOT, it.second) },
            ),
            now,
        )
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

        const val COARSE_COLS = 8
        const val COARSE_ROWS = 10

        /** Même emprise que [grid] avec moins de points (prévision au-delà de +6 h). */
        fun coarse(grid: ForecastGrid): ForecastGrid {
            val north = grid.south + grid.latStep * (grid.rows - 1)
            val east = grid.west + grid.lonStep * (grid.cols - 1)
            return ForecastGrid(
                south = grid.south,
                west = grid.west,
                latStep = (north - grid.south) / (COARSE_ROWS - 1),
                lonStep = (east - grid.west) / (COARSE_COLS - 1),
                rows = COARSE_ROWS,
                cols = COARSE_COLS,
            )
        }

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
    // Nuages : valeurs horaires, partagées par les 4 quarts d'heure de chaque heure.
    val hours = points.first().hourly?.time.orEmpty()
    val cloudsByHour = hours.indices.associate { h ->
        hours[h] to CloudLayer(grid, FloatArray(grid.rows * grid.cols) { k -> (points[k].hourly?.cloudCover?.getOrNull(h) ?: 0.0).toFloat() })
    }
    return times.indices
        .filter { times[it] + 15 * 60 > now }
        .map { i ->
            MapFrame.Forecast(
                grid = grid,
                time = times[i],
                values = FloatArray(grid.rows * grid.cols) { k ->
                    (points[k].minutely15.precipitation.getOrNull(i) ?: 0.0).toFloat()
                },
                clouds = cloudsByHour[times[i] - Math.floorMod(times[i], 3600L)],
            )
        }
}

/** Images de prévision ; [partial] : incomplète (Open-Meteo indisponible), à recharger vite. */
data class ForecastFrames(val frames: List<MapFrame>, val partial: Boolean = false)

/** Avancement du chargement : libellé de l'étape et, si connu, nombre d'éléments reçus. */
data class LoadProgress(val label: String, val done: Int = 0, val total: Int = 0)
