package fr.jbrunet.yama.data.repository

import fr.jbrunet.yama.data.arome.AromePiSource
import fr.jbrunet.yama.data.remote.GridPointDto
import fr.jbrunet.yama.data.remote.OpenMeteoBusyException
import fr.jbrunet.yama.data.remote.OpenMeteoQuota
import fr.jbrunet.yama.data.remote.OpenMeteoApi
import fr.jbrunet.yama.data.remote.RainViewerApi
import fr.jbrunet.yama.data.remote.RainViewerMapsDto
import fr.jbrunet.yama.domain.CloudLayer
import fr.jbrunet.yama.domain.ForecastGrid
import fr.jbrunet.yama.domain.MapFrame
import fr.jbrunet.yama.domain.Place
import fr.jbrunet.yama.domain.RainMap
import fr.jbrunet.yama.domain.WindFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/**
 * Rain map: observed RainViewer radar (past) + Open-Meteo forecast grid
 * at 15 min around the place (future). See ARCHITECTURE.md.
 */
class RainMapRepository(
    private val openMeteo: OpenMeteoApi,
    private val rainViewer: RainViewerApi,
    private val arome: AromePiSource = AromePiSource(),
    /** Météo-France key entered in the settings (null: Open-Meteo only). */
    private val aromeKey: suspend () -> String? = { null },
) {

    /** Current step of the map loading (null: nothing in progress), shown on screen. */
    val progress = MutableStateFlow<LoadProgress?>(null)

    /** RainViewer radar frames (recent past), quick to get. */
    suspend fun radar(): List<MapFrame> = runCatching { rainViewer.maps().toFrames() }.getOrDefault(emptyList())

    suspend fun load(
        center: Place,
        grid: ForecastGrid = gridAround(center),
        now: Long = System.currentTimeMillis() / 1000,
    ): RainMap = withContext(Dispatchers.Default) {
        val radar = async { runCatching { rainViewer.maps().toFrames() }.getOrDefault(emptyList()) }
        val forecast = async { runCatching { forecastFor(grid, now).frames } }
        // Radar and forecast are independent: one may fail without hiding the other.
        val frames = radar.await() + forecast.await().getOrNull().orEmpty()
        if (frames.isEmpty()) throw forecast.await().exceptionOrNull() ?: IllegalStateException("No rain data")
        RainMap(grid, frames)
    }

    /** Forecast areas already downloaded (most recent first). */
    private val cache = ArrayDeque<CachedGrid>()

    private class CachedGrid(val grid: ForecastGrid, val frames: List<MapFrame>, val at: Long)

    /**
     * Forecast already in memory (< [CACHE_SECONDS]) whose grid suits this view:
     * coming back to an area already seen costs no Open-Meteo call.
     */
    fun cachedFor(view: Bounds, now: Long = System.currentTimeMillis() / 1000): Pair<ForecastGrid, List<MapFrame>>? =
        synchronized(cache) {
            cache.removeAll { now - it.at > CACHE_SECONDS }
            cache.firstOrNull { !needsNewGrid(it.grid, view) }
                ?.let { c -> c.grid to c.frames.filter { it.time + 15 * 60 > now } }
                ?.takeIf { it.second.isNotEmpty() }
        }

    /**
     * Forecast only, for a given grid (visible area of the map), cached.
     * With a Météo-France key and an area in France: AROME-PI for the first 6 hours
     * (1.3 km grid), Open-Meteo afterwards; otherwise (or on failure) Open-Meteo only.
     */
    suspend fun forecastFor(grid: ForecastGrid, now: Long = System.currentTimeMillis() / 1000): ForecastFrames =
        // Costly GeoTIFF / JSON decoding: never on the main thread (the app would freeze).
        withContext(Dispatchers.Default) {
            val key = aromeKey()?.takeIf { it.isNotBlank() && AromePiSource.covers(grid) }
            progress.value = LoadProgress(if (key != null) LoadStage.METEO_FRANCE else LoadStage.OPEN_METEO)
            val aromeFrames = key?.let {
                async {
                    runCatching {
                        arome.forecast(it, grid, now) { done, total -> progress.value = LoadProgress(LoadStage.METEO_FRANCE, done, total) }
                    }.getOrDefault(emptyList())
                }
            }
            // With AROME-PI, Open-Meteo is only used beyond +6 h: a coarse grid is enough
            // (≈ 80 points instead of 192) and spares the per-minute quota.
            val openMeteoGrid = if (key != null) coarse(grid) else grid
            val openMeteoFrames = runCatching { openMeteoFrames(openMeteoGrid, now) }
            val precise = aromeFrames?.await().orEmpty()
            val after = precise.lastOrNull()?.time ?: Long.MIN_VALUE
            // AROME-PI has no clouds: take Open-Meteo's for the same time.
            val cloudsByHour = openMeteoFrames.getOrNull().orEmpty().filterIsInstance<MapFrame.Forecast>()
                .associate { it.time - Math.floorMod(it.time, 3600L) to it.clouds }
            val withClouds = precise.map { f ->
                if (f is MapFrame.Forecast && f.clouds == null) MapFrame.Forecast(f.time, f.values, f.grid, f.source, cloudsByHour[f.time - Math.floorMod(f.time, 3600L)]) else f
            }
            val frames = withClouds + openMeteoFrames.getOrNull().orEmpty().filter { it.time > after }
            if (frames.isEmpty()) openMeteoFrames.getOrThrow()
            // Incomplete forecast (Open-Meteo rate limit): not cached, and quick retry.
            val partial = openMeteoFrames.isFailure
            if (!partial) cacheFrames(grid, frames, now)
            ForecastFrames(frames, partial)
        }

    /**
     * Current rain over [grid] for the map widget: a single AROME-PI image if a key is
     * active (1-2 requests), otherwise Open-Meteo on a coarse grid (80 points) if [allowOpenMeteo].
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
        // Open-Meteo rate limit: the map waits, to leave the quota to the home screen.
        if (OpenMeteoQuota.isBlocked) throw OpenMeteoBusyException()
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

    /** Wind on a coarse grid (≈ 80 points) covering [grid], 24 h in 15 min steps. */
    suspend fun wind(grid: ForecastGrid, now: Long = System.currentTimeMillis() / 1000): List<WindFrame> =
        withContext(Dispatchers.Default) {
            if (OpenMeteoQuota.isBlocked) throw OpenMeteoBusyException()
            val g = coarse(grid)
            val points = g.coordinates()
            val dto = openMeteo.windGrid(
                latitudes = points.joinToString(",") { "%.4f".format(Locale.ROOT, it.first) },
                longitudes = points.joinToString(",") { "%.4f".format(Locale.ROOT, it.second) },
            )
            windFrames(g, dto, now)
        }

    companion object {
        // 12 × 16 points (portrait) per Open-Meteo call; each point counts as one
        // call in the free quota, hence a modest grid recomputed only when needed.
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

        /** Same extent as [grid] with fewer points (forecast beyond +6 h). */
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

        /** Grid covering the visible area (+10 % margin), same number of points whatever the zoom. */
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
         * Should the forecast be reloaded for this view? Yes if the current grid no longer covers
         * the visible area, or if its spacing is 2× too coarse / too fine for the zoom.
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

/** Geographic extent (degrees). */
data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double)

/** Coordinates of the points, row by row starting from the south-west. */
internal fun ForecastGrid.coordinates(): List<Pair<Double, Double>> =
    (0 until rows).flatMap { r -> (0 until cols).map { c -> (south + r * latStep) to (west + c * lonStep) } }

internal fun RainViewerMapsDto.toFrames(): List<MapFrame> = radar.past.map {
    MapFrame.Radar(it.time, "$host${it.path}/256/{z}/{x}/{y}/2/1_1.png")
}

/** One image per 15 min step, from the current quarter hour. */
internal fun forecastFrames(grid: ForecastGrid, points: List<GridPointDto>, now: Long): List<MapFrame> {
    if (points.size != grid.rows * grid.cols) return emptyList()
    val times = points.first().minutely15.time
    // Clouds: hourly values, shared by the 4 quarter hours of each hour.
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

/** Forecast frames; [partial]: incomplete (Open-Meteo unavailable), reload soon. */
data class ForecastFrames(val frames: List<MapFrame>, val partial: Boolean = false)

/** Loading progress: step and, when known, number of items received. */
enum class LoadStage { METEO_FRANCE, OPEN_METEO, PREPARING }

data class LoadProgress(val stage: LoadStage, val done: Int = 0, val total: Int = 0)

/** Open-Meteo points (order of [ForecastGrid.coordinates]) → one wind frame per upcoming quarter hour. */
internal fun windFrames(grid: ForecastGrid, points: List<fr.jbrunet.yama.data.remote.WindPointDto>, now: Long): List<WindFrame> {
    if (points.size != grid.rows * grid.cols) return emptyList()
    val times = points.first().minutely15.time
    return times.indices.filter { times[it] + 15 * 60 > now }.map { i ->
        WindFrame(
            time = times[i],
            grid = grid,
            speed = FloatArray(points.size) { k -> (points[k].minutely15.speed.getOrNull(i) ?: 0.0).toFloat() },
            direction = FloatArray(points.size) { k -> (points[k].minutely15.direction.getOrNull(i) ?: 0.0).toFloat() },
            gusts = FloatArray(points.size) { k -> (points[k].minutely15.gusts.getOrNull(i) ?: 0.0).toFloat() },
        )
    }
}
