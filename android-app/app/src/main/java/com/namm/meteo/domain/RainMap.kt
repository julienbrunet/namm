package com.namm.meteo.domain

/** Une image de la carte : radar observé (passé) ou grille de prévision (futur). */
sealed interface MapFrame {
    /** Horodatage unix (secondes). */
    val time: Long

    data class Radar(override val time: Long, val tileUrlTemplate: String) : MapFrame

    /**
     * Prévision sur une grille régulière lat/lon : valeurs en mm / 15 min, rangées ligne par ligne
     * ([values] de taille rows × cols, lues par `this[ligne, colonne]`) ; ligne 0 = sud.
     * Tableau primitif (pas de Double « boxés ») et égalité par identité : une image sert de clé
     * de cache sans recalculer un hashCode sur toute la grille.
     */
    class Forecast(
        override val time: Long,
        val values: FloatArray,
        val grid: ForecastGrid,
        val source: ForecastSource = ForecastSource.OPEN_METEO,
    ) : MapFrame {
        init {
            require(values.size == grid.rows * grid.cols) { "Grille ${grid.rows}×${grid.cols} mais ${values.size} valeurs" }
        }

        operator fun get(row: Int, col: Int): Float = values[row * grid.cols + col]
    }
}

enum class ForecastSource(val label: String) {
    OPEN_METEO("Prévision Open-Meteo (AROME / ICON)"),
    AROME_PI("Prévision Météo-France AROME-PI"),
}

data class ForecastGrid(
    val south: Double,
    val west: Double,
    val latStep: Double,
    val lonStep: Double,
    val rows: Int,
    val cols: Int,
)

data class RainMap(val grid: ForecastGrid, val frames: List<MapFrame>) {
    /** Remplace la prévision (nouvelle zone visible) en gardant les images radar. */
    fun withForecast(newGrid: ForecastGrid, forecast: List<MapFrame>) =
        RainMap(newGrid, frames.filterIsInstance<MapFrame.Radar>() + forecast)

    /** Index de la première image de prévision (« maintenant »). */
    val nowIndex: Int get() = frames.indexOfFirst { it is MapFrame.Forecast }.coerceAtLeast(0)
}
