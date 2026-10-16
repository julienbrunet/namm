package com.namm.meteo.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/** API Open-Meteo : gratuite, sans clé. https://open-meteo.com/en/docs */
interface OpenMeteoApi {

    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m",
        @Query("minutely_15") minutely15: String = "precipitation",
        @Query("hourly") hourly: String = "temperature_2m,precipitation_probability,weather_code",
        @Query("daily") daily: String = "temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code",
        @Query("models") models: String = "best_match",
        @Query("timezone") timezone: String = "auto",
        @Query("forecast_days") forecastDays: Int = 7,
    ): ForecastDto

    /** Grille multi-points pour la carte de prévision (pas de 15 min, horodatage unix). */
    @GET("v1/forecast")
    suspend fun grid(
        @Query("latitude") latitudes: String,
        @Query("longitude") longitudes: String,
        @Query("minutely_15") minutely15: String = "precipitation",
        @Query("forecast_minutely_15") steps: Int = 24,
        @Query("timeformat") timeFormat: String = "unixtime",
    ): List<GridPointDto>

    companion object {
        fun create(): OpenMeteoApi = Network.create("https://api.open-meteo.com/")
    }
}

/** API de géocodage Open-Meteo (recherche de ville). */
interface GeocodingApi {
    @GET("v1/search")
    suspend fun search(
        @Query("name") name: String,
        @Query("count") count: Int = 10,
        @Query("language") language: String = "fr",
    ): GeocodingDto

    companion object {
        fun create(): GeocodingApi = Network.create("https://geocoding-api.open-meteo.com/")
    }
}

@Serializable
data class GeocodingDto(val results: List<PlaceDto> = emptyList())

@Serializable
data class PlaceDto(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    val admin1: String? = null,
)

@Serializable
data class GridPointDto(
    val latitude: Double,
    val longitude: Double,
    @SerialName("minutely_15") val minutely15: GridSeriesDto,
)

@Serializable
data class GridSeriesDto(val time: List<Long>, val precipitation: List<Double?>)

@Serializable
data class ForecastDto(
    val latitude: Double,
    val longitude: Double,
    val timezone: String = "GMT",
    val current: CurrentDto? = null,
    @SerialName("minutely_15") val minutely15: Minutely15Dto? = null,
    val hourly: HourlyDto? = null,
    val daily: DailyDto? = null,
)

@Serializable
data class CurrentDto(
    val time: String,
    @SerialName("temperature_2m") val temperature: Double,
    @SerialName("apparent_temperature") val apparentTemperature: Double,
    @SerialName("relative_humidity_2m") val humidity: Int,
    val precipitation: Double,
    @SerialName("weather_code") val weatherCode: Int,
    @SerialName("wind_speed_10m") val windSpeed: Double,
)

@Serializable
data class Minutely15Dto(
    val time: List<String>,
    val precipitation: List<Double?>,
)

@Serializable
data class HourlyDto(
    val time: List<String>,
    @SerialName("temperature_2m") val temperature: List<Double?>,
    @SerialName("precipitation_probability") val precipitationProbability: List<Int?> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
)

@Serializable
data class DailyDto(
    val time: List<String>,
    @SerialName("temperature_2m_max") val tempMax: List<Double>,
    @SerialName("temperature_2m_min") val tempMin: List<Double>,
    @SerialName("precipitation_sum") val precipitationSum: List<Double>,
    @SerialName("weather_code") val weatherCode: List<Int>,
)
