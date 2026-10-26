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
    /** Non nul quand la mise à jour a échoué et que l'on affiche la dernière prévision connue. */
    val staleReason: StaleReason? = null,
) {
    val offline: Boolean get() = staleReason != null
}

enum class StaleReason(val label: String) {
    NO_NETWORK("pas de réseau"),
    RATE_LIMITED("Open-Meteo saturé"),
    ERROR("service indisponible"),
}

internal fun Throwable.staleReason(): StaleReason = when {
    this is retrofit2.HttpException && code() == 429 -> StaleReason.RATE_LIMITED
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
     * Lieu à utiliser : la position si activée et disponible, sinon la ville choisie.
     * En arrière-plan ([background]), dernière position connue, ou à défaut celle de la
     * dernière prévision (plutôt que la ville par défaut).
     */
    suspend fun resolvePlace(background: Boolean = false): Place {
        val s = settings.current()
        if (!s.useLocation) return s.place
        return location.currentPlace(fresh = !background)
            ?: cache.read()?.place?.takeIf { background && it.detail == LocationProvider.CURRENT_DETAIL }
            ?: s.place
    }

    /** Télécharge la prévision ; en cas d'échec réseau, renvoie la dernière prévision en cache. */
    suspend fun load(place: Place? = null, background: Boolean = false): LocalForecast {
        val target = place ?: resolvePlace(background)
        return try {
            // Vie privée : position arrondie à ~1 km, sans effet sur une prévision météo.
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

/** Coordonnée arrondie à 0,01° (≈ 1 km) avant envoi à Open-Meteo. */
internal fun roundCoordinate(value: Double): Double = kotlin.math.round(value * 100) / 100

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
