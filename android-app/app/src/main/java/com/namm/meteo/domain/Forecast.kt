package com.namm.meteo.domain

data class Forecast(
    val current: CurrentWeather,
    val nextPrecipitation: List<PrecipitationStep>,
    val days: List<DailyForecast>,
)

data class CurrentWeather(
    val temperature: Double,
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
