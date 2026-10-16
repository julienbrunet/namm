package com.namm.meteo.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

/** API Open-Meteo : gratuite, sans clé. https://open-meteo.com/en/docs */
interface OpenMeteoApi {

    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "temperature_2m,precipitation,weather_code,wind_speed_10m",
        @Query("minutely_15") minutely15: String = "precipitation",
        @Query("hourly") hourly: String = "temperature_2m,precipitation_probability,precipitation",
        @Query("daily") daily: String = "temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code",
        @Query("models") models: String = "best_match",
        @Query("timezone") timezone: String = "auto",
        @Query("forecast_days") forecastDays: Int = 7,
    ): ForecastDto

    companion object {
        private const val BASE_URL = "https://api.open-meteo.com/"

        fun create(): OpenMeteoApi {
            val json = Json { ignoreUnknownKeys = true }
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(OpenMeteoApi::class.java)
        }
    }
}

@Serializable
data class ForecastDto(
    val latitude: Double,
    val longitude: Double,
    val current: CurrentDto? = null,
    @SerialName("minutely_15") val minutely15: Minutely15Dto? = null,
    val daily: DailyDto? = null,
)

@Serializable
data class CurrentDto(
    val time: String,
    @SerialName("temperature_2m") val temperature: Double,
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
data class DailyDto(
    val time: List<String>,
    @SerialName("temperature_2m_max") val tempMax: List<Double>,
    @SerialName("temperature_2m_min") val tempMin: List<Double>,
    @SerialName("precipitation_sum") val precipitationSum: List<Double>,
    @SerialName("weather_code") val weatherCode: List<Int>,
)
