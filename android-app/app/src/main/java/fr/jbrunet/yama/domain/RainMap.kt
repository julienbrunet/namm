package fr.jbrunet.yama.domain

/** One map frame: observed radar (past) or forecast grid (future). */
sealed interface MapFrame {
    /** Unix timestamp (seconds). */
    val time: Long

    data class Radar(override val time: Long, val tileUrlTemplate: String) : MapFrame

    /**
     * Forecast on a regular lat/lon grid: values in mm / 15 min, stored row by row
     * ([values] of size rows × cols, read with `this[row, column]`); row 0 = south.
     * Primitive array (no boxed Doubles) and identity equality: a frame can be a cache key
     * without computing a hashCode over the whole grid.
     */
    class Forecast(
        override val time: Long,
        val values: FloatArray,
        val grid: ForecastGrid,
        val source: ForecastSource = ForecastSource.OPEN_METEO,
        /** Forecast cloud cover (Open-Meteo) at this time, on its own grid. */
        val clouds: CloudLayer? = null,
    ) : MapFrame {
        init {
            require(values.size == grid.rows * grid.cols) { "Grid ${grid.rows}×${grid.cols} but ${values.size} values" }
        }

        operator fun get(row: Int, col: Int): Float = values[row * grid.cols + col]
    }
}

/** Cloud cover (%) stored row by row like [MapFrame.Forecast.values]; identity equality. */
class CloudLayer(val grid: ForecastGrid, val cover: FloatArray) {
    operator fun get(row: Int, col: Int): Float = cover[row * grid.cols + col]
}

/**
 * 10 m wind on a grid (stored row by row, row 0 = south): speed and gusts in km/h,
 * direction the wind comes from in degrees (0 = north, 90 = east).
 */
class WindFrame(val time: Long, val grid: ForecastGrid, val speed: FloatArray, val direction: FloatArray, val gusts: FloatArray)

enum class ForecastSource { OPEN_METEO, AROME_PI }

data class ForecastGrid(
    val south: Double,
    val west: Double,
    val latStep: Double,
    val lonStep: Double,
    val rows: Int,
    val cols: Int,
)

data class RainMap(val grid: ForecastGrid, val frames: List<MapFrame>) {
    /** Replaces the forecast (new visible area) while keeping the radar frames. */
    fun withForecast(newGrid: ForecastGrid, forecast: List<MapFrame>) =
        RainMap(newGrid, frames.filterIsInstance<MapFrame.Radar>() + forecast)

    /** Index of the first forecast frame (“now”). */
    val nowIndex: Int get() = frames.indexOfFirst { it is MapFrame.Forecast }.coerceAtLeast(0)
}
