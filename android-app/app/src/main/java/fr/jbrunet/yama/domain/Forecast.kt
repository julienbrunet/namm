package fr.jbrunet.yama.domain

data class Forecast(
    val current: CurrentWeather,
    val nextPrecipitation: List<PrecipitationStep>,
    /** Prochaines 24 h, heure par heure (à partir de l'heure en cours). */
    val hours: List<HourlyForecast> = emptyList(),
    val days: List<DailyForecast>,
)

data class CurrentWeather(
    /** Heure locale ISO, ex. "2026-10-16T10:30". */
    val time: String,
    val temperature: Double,
    val apparentTemperature: Double,
    val humidity: Int,
    val precipitation: Double,
    val weatherCode: Int,
    val windSpeed: Double,
)

/** Précipitation sur un pas de 15 minutes (mm). */
data class PrecipitationStep(val time: String, val millimeters: Double)

data class DailyForecast(
    val date: String,
    val tempMin: Double,
    val tempMax: Double,
    val precipitationSum: Double,
    val weatherCode: Int,
    /** Lever et coucher du soleil, heure locale ISO (absents d'un ancien cache). */
    val sunrise: String? = null,
    val sunset: String? = null,
)

data class HourlyForecast(
    /** Heure locale ISO, ex. "2026-10-16T21:00". */
    val time: String,
    val temperature: Double,
    val precipitationProbability: Int,
    val weatherCode: Int,
)
