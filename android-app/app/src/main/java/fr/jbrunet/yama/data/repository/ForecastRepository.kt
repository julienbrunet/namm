package fr.jbrunet.yama.data.repository

import fr.jbrunet.yama.data.local.CachedForecast
import fr.jbrunet.yama.data.local.ForecastCache
import fr.jbrunet.yama.data.local.SettingsStore
import fr.jbrunet.yama.data.location.LocationProvider
import fr.jbrunet.yama.data.remote.ForecastDto
import fr.jbrunet.yama.data.remote.OpenMeteoApi
import fr.jbrunet.yama.domain.CurrentWeather
import fr.jbrunet.yama.domain.DailyForecast
import fr.jbrunet.yama.domain.Forecast
import fr.jbrunet.yama.domain.HourlyForecast
import fr.jbrunet.yama.domain.Place
import fr.jbrunet.yama.domain.PrecipitationStep

/** Forecast for a place, with the download time and an offline flag. */
data class LocalForecast(
    val place: Place,
    val forecast: Forecast,
    val fetchedAtMillis: Long,
    /** Not null when the update failed and the last known forecast is shown. */
    val staleReason: StaleReason? = null,
) {
    val offline: Boolean get() = staleReason != null
}

enum class StaleReason { NO_NETWORK, RATE_LIMITED, ERROR }

internal fun Throwable.staleReason(): StaleReason = when {
    this is retrofit2.HttpException && code() == 429 -> StaleReason.RATE_LIMITED
    this is fr.jbrunet.yama.data.remote.OpenMeteoBusyException -> StaleReason.RATE_LIMITED
    this is java.net.UnknownHostException || this is java.net.ConnectException ||
        this is java.net.SocketTimeoutException -> StaleReason.NO_NETWORK
    else -> StaleReason.ERROR
}

class ForecastRepository(
    private val api: OpenMeteoApi,
    private val cache: ForecastCache,
    private val settings: SettingsStore,
    private val location: LocationProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Place to use: the position if enabled and available, otherwise the chosen city.
     * In the background ([background]), last known position, or failing that the one of the
     * last forecast (rather than the default city).
     */
    suspend fun resolvePlace(background: Boolean = false): Place {
        val s = settings.current()
        if (!s.useLocation) return s.place
        return location.currentPlace(fresh = !background)
            ?: cache.read()?.place?.takeIf { background && it.detail == LocationProvider.CURRENT_DETAIL }
            ?: s.place
    }

    /** Downloads the forecast; on a network failure, returns the last cached forecast. */
    suspend fun load(place: Place? = null, background: Boolean = false): LocalForecast {
        val target = place ?: resolvePlace(background)
        return try {
            // Privacy: position rounded to ~1 km, which makes no difference to a weather forecast.
            val dto = api.forecast(roundCoordinate(target.latitude), roundCoordinate(target.longitude))
            val now = clock()
            cache.write(CachedForecast(target, now, dto))
            LocalForecast(target, dto.toDomain(), now)
        } catch (e: Exception) {
            cached()?.copy(staleReason = e.staleReason()) ?: throw e
        }
    }

    @Volatile
    private var domainMemo: Pair<CachedForecast, LocalForecast>? = null

    fun cached(): LocalForecast? {
        val raw = cache.read() ?: return null
        domainMemo?.takeIf { it.first === raw }?.let { return it.second }
        return LocalForecast(raw.place, raw.forecast.toDomain(), raw.fetchedAtMillis).also { domainMemo = raw to it }
    }
}

/** Coordinate rounded to 0.01° (≈ 1 km) before being sent to Open-Meteo. */
internal fun roundCoordinate(value: Double): Double = kotlin.math.round(value * 100) / 100

/** Number of 15 min steps shown (2 h). */
private const val NOWCAST_STEPS = 8

/** Number of hours in the temperature chart. */
private const val HOURLY_COUNT = 24

internal fun ForecastDto.toDomain(): Forecast {
    val c = requireNotNull(current) { "Response without current weather" }
    // minutely_15 starts at midnight: only keep the steps from the current quarter hour.
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
            DailyForecast(
                d.time[i], d.tempMin[i], d.tempMax[i], d.precipitationSum[i], d.weatherCode[i],
                d.sunrise.getOrNull(i), d.sunset.getOrNull(i),
            )
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
