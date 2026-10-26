package com.namm.meteo.work

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.toArgb
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.namm.meteo.MeteoApp
import com.namm.meteo.data.repository.RainMapRepository
import com.namm.meteo.domain.ForecastSource
import com.namm.meteo.domain.Place
import com.namm.meteo.domain.minutesUntilRain
import com.namm.meteo.ui.theme.DarkSlate
import com.namm.meteo.ui.theme.LightSlate
import com.namm.meteo.widget.WidgetMapRenderer
import com.namm.meteo.widget.WidgetMapStore
import com.namm.meteo.widget.Widgets
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Toutes les 15 min (minimum Android, retardable en veille) : rafraîchit la prévision (cache + widget), prévient si la pluie
 * arrive dans l'heure, et vérifie une fois par jour la présence d'une nouvelle version.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MeteoApp
        applicationContext.getSharedPreferences("worker", Context.MODE_PRIVATE).edit()
            .putLong("last_run", System.currentTimeMillis()).apply()
        val settings = app.settings.current()
        val result = runCatching { app.forecastRepository.load(background = true) }.getOrNull()
        if (result != null && Widgets.hasMapWidget(applicationContext)) {
            runCatching { renderMapWidget(app, result.place) }
        }
        Widgets.updateAll(applicationContext)

        if (result != null && !result.offline && settings.rainAlerts) {
            val minutes = minutesUntilRain(result.forecast.nextPrecipitation)
            val prefs = applicationContext.getSharedPreferences("worker", Context.MODE_PRIVATE)
            // Une seule alerte par épisode : on mémorise l'heure de début annoncée.
            val rainStart = result.forecast.nextPrecipitation.getOrNull((minutes ?: 0) / 15)?.time
            if (minutes != null && minutes <= 60 && prefs.getString("last_rain", null) != rainStart) {
                Notifications.rain(applicationContext, result.place.name, minutes)
                prefs.edit().putString("last_rain", rainStart).apply()
            }
        }

        if (settings.autoUpdate) {
            val prefs = applicationContext.getSharedPreferences("worker", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("last_update_check", 0) > TimeUnit.HOURS.toMillis(12)) {
                prefs.edit().putLong("last_update_check", now).apply()
                runCatching { app.updateManager.check() }.getOrNull()?.let {
                    Notifications.update(applicationContext, it.version.toString())
                }
            }
        }
        return Result.success()
    }

    /**
     * Image du widget carte : pluie actuelle sur ≈ 50 km autour du lieu, au format du widget.
     * Avec AROME-PI : à chaque passage (15 min, 1-2 requêtes) ; sans clé, Open-Meteo toutes les 30 min.
     */
    private suspend fun renderMapWidget(app: MeteoApp, place: Place) {
        val prefs = applicationContext.getSharedPreferences("widget_map", Context.MODE_PRIVATE)
        val settings = app.settings.current()
        val nowMs = System.currentTimeMillis()
        val allowOpenMeteo = nowMs - prefs.getLong("last_open_meteo", 0) > TimeUnit.MINUTES.toMillis(29)

        val (width, height) = Widgets.mapWidgetSizePx(applicationContext)
        val z = WidgetMapRenderer.zoomFor(place.latitude, height)
        val grid = RainMapRepository.gridFor(WidgetMapRenderer.bounds(place.latitude, place.longitude, width, height, z))
        val frame = app.rainMapRepository.currentFrame(grid, allowOpenMeteo)
        if (frame == null && !allowOpenMeteo) return // on garde l'image précédente
        if (frame?.source == ForecastSource.OPEN_METEO) prefs.edit().putLong("last_open_meteo", nowMs).apply()

        val dark = settings.style.widgetDark(
            settings.theme,
            (applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
        )
        val tiles = WidgetMapRenderer.TileCache(File(applicationContext.cacheDir, "widget_tiles")).also { it.purge() }
        val bitmap = WidgetMapRenderer.render(
            width = width, height = height, lat = place.latitude, lon = place.longitude, z = z, frame = frame, dark = dark,
            background = (if (dark) DarkSlate else LightSlate).tile.toArgb(),
        ) { tz, x, y -> tiles.get(tz, x, y) }
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        val zone = ZoneId.systemDefault()
        val label = buildString {
            append(place.name)
            frame?.let {
                append(" · pluie à ").append(fmt.format(Instant.ofEpochSecond(it.time).atZone(zone)))
                append(" · ").append(if (it.source == ForecastSource.AROME_PI) "AROME-PI" else "Open-Meteo")
            }
            append(" · maj ").append(fmt.format(Instant.ofEpochMilli(nowMs).atZone(zone)))
        }
        WidgetMapStore.write(applicationContext, bitmap, label)
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES) // minimum Android
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
