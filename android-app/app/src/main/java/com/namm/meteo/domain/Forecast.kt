package com.namm.meteo.domain

data class Forecast(
    val current: CurrentWeather,
    val nextPrecipitation: List<PrecipitationStep>,
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
)
