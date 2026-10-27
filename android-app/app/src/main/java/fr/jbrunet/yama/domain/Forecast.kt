package fr.jbrunet.yama.domain

data class Forecast(
    val current: CurrentWeather,
    val nextPrecipitation: List<PrecipitationStep>,
    /** Next 24 h, hour by hour (from the current hour). */
    val hours: List<HourlyForecast> = emptyList(),
    val days: List<DailyForecast>,
)

data class CurrentWeather(
    /** Local ISO time, e.g. "2026-10-16T10:30". */
    val time: String,
    val temperature: Double,
    val apparentTemperature: Double,
    val humidity: Int,
    val precipitation: Double,
    val weatherCode: Int,
    val windSpeed: Double,
)

/** Precipitation over a 15 minute step (mm). */
data class PrecipitationStep(val time: String, val millimeters: Double)

data class DailyForecast(
    val date: String,
    val tempMin: Double,
    val tempMax: Double,
    val precipitationSum: Double,
    val weatherCode: Int,
    /** Sunrise and sunset, local ISO time (missing from an old cache). */
    val sunrise: String? = null,
    val sunset: String? = null,
)

data class HourlyForecast(
    /** Local ISO time, e.g. "2026-10-16T21:00". */
    val time: String,
    val temperature: Double,
    val precipitationProbability: Int,
    val weatherCode: Int,
)
