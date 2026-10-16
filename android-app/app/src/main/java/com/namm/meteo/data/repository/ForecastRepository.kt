package com.namm.meteo.data.repository

import com.namm.meteo.data.local.CachedForecast
import com.namm.meteo.data.local.ForecastCache
import com.namm.meteo.data.local.SettingsStore
import com.namm.meteo.data.location.LocationProvider
import com.namm.meteo.data.remote.ForecastDto
import com.namm.meteo.data.remote.OpenMeteoApi
import com.namm.meteo.domain.CurrentWeather
import com.namm.meteo.domain.DailyForecast
import com.namm.meteo.domain.Forecast
import com.namm.meteo.domain.HourlyForecast
import com.namm.meteo.domain.Place
import com.namm.meteo.domain.PrecipitationStep

/** Prévision pour un lieu, avec l'heure de téléchargement et un indicateur hors-ligne. */
data class LocalForecast(
    val place: Place,
    val forecast: Forecast,
    val fetchedAtMillis: Long,
    val offline: Boolean = false,
)

class ForecastRepository(
    private val api: OpenMeteoApi,
    private val cache: ForecastCache,
    private val settings: SettingsStore,
    private val location: LocationProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Lieu à utiliser : la position si activée et disponible, sinon la ville choisie. */
    suspend fun resolvePlace(): Place {
        val s = settings.current()
        return if (s.useLocation) location.currentPlace() ?: s.place else s.place
    }

    /** Télécharge la prévision ; en cas d'échec réseau, renvoie la dernière prévision en cache. */
    suspend fun load(place: Place? = null): LocalForecast {
        val target = place ?: resolvePlace()
        return try {
            val dto = api.forecast(target.latitude, target.longitude)
            val now = clock()
            cache.write(CachedForecast(target, now, dto))
            LocalForecast(target, dto.toDomain(), now)
        } catch (e: Exception) {
            cached()?.copy(offline = true) ?: throw e
        }
    }

    fun cached(): LocalForecast? = cache.read()?.let {
        LocalForecast(it.place, it.forecast.toDomain(), it.fetchedAtMillis)
    }
}

/** Nombre de pas de 15 min affichés (2 h). */
private const val NOWCAST_STEPS = 8

/** Nombre d'heures du graphique de températures. */
private const val HOURLY_COUNT = 24

internal fun ForecastDto.toDomain(): Forecast {
    val c = requireNotNull(current) { "Réponse sans données actuelles" }
    // minutely_15 commence à minuit : on ne garde que les pas à partir du quart d'heure courant.
    val start = c.time.dropLast(2) + floorQuarter(c.time)
    val steps = minutely15?.let { m ->
        m.time.zip(m.precipitation)
            .dropWhile { (t, _) -> t < start }
            .take(NOWCAST_STEPS)
            .map { (t, p) -> PrecipitationStep(t, p ?: 0.0) }
    }.orEmpty()
    val hours = hourly?.let { h ->
        val currentHour = c.time.take(13) + ":00"
        h.time.indices
            .filter { h.time[it] >= currentHour && h.temperature[it] != null }
            .take(HOURLY_COUNT)
            .map { i ->
                HourlyForecast(
                    time = h.time[i],
                    temperature = h.temperature[i]!!,
                    precipitationProbability = h.precipitationProbability.getOrNull(i) ?: 0,
                    weatherCode = h.weatherCode.getOrNull(i) ?: c.weatherCode,
                )
            }
    }.orEmpty()
    val days = daily?.let { d ->
        d.time.indices.map { i ->
            DailyForecast(d.time[i], d.tempMin[i], d.tempMax[i], d.precipitationSum[i], d.weatherCode[i])
        }
    }.orEmpty()
    return Forecast(
        current = CurrentWeather(
            time = c.time,
            temperature = c.temperature,
            apparentTemperature = c.apparentTemperature,
            humidity = c.humidity,
            precipitation = c.precipitation,
            weatherCode = c.weatherCode,
            windSpeed = c.windSpeed,
        ),
        nextPrecipitation = steps,
        hours = hours,
        days = days,
    )
}

/** "2026-10-16T10:37" -> "30" */
private fun floorQuarter(isoTime: String): String {
    val minutes = isoTime.substringAfterLast(':').toIntOrNull() ?: 0
    return "%02d".format(minutes / 15 * 15)
}
