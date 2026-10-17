package com.namm.meteo.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.namm.meteo.MeteoApp
import com.namm.meteo.domain.minutesUntilRain
import androidx.compose.ui.graphics.toArgb
import com.namm.meteo.domain.MapFrame
import com.namm.meteo.ui.theme.DarkSlate
import com.namm.meteo.ui.theme.LightSlate
import com.namm.meteo.ui.theme.ThemeMode
import com.namm.meteo.widget.WidgetMapRenderer
import com.namm.meteo.widget.WidgetMapStore
import com.namm.meteo.widget.Widgets
import java.util.concurrent.TimeUnit

/**
 * Toutes les 30 min : rafraîchit la prévision (cache + widget), prévient si la pluie
 * arrive dans l'heure, et vérifie une fois par jour la présence d'une nouvelle version.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MeteoApp
        val settings = app.settings.current()
        val result = runCatching { app.forecastRepository.load() }.getOrNull()
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

    /** Image du widget carte : prévision de pluie actuelle autour du lieu. */
    private suspend fun renderMapWidget(app: MeteoApp, place: com.namm.meteo.domain.Place) {
        val map = app.rainMapRepository.load(place)
        val now = System.currentTimeMillis() / 1000
        val frame = map.frames.filterIsInstance<MapFrame.Forecast>().firstOrNull { it.time + 15 * 60 > now }
        val settings = app.settings.current()
        val dark = when (settings.theme) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> (applicationContext.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val tiles = WidgetMapRenderer.TileCache(java.io.File(applicationContext.cacheDir, "widget_tiles"))
        val bitmap = WidgetMapRenderer.render(
            width = 600, height = 480, lat = place.latitude, lon = place.longitude, frame = frame, dark = dark,
            background = (if (dark) DarkSlate else LightSlate).tile.toArgb(),
        ) { z, x, y -> tiles.get(z, x, y) }
        val time = frame?.let {
            java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                .format(java.time.Instant.ofEpochSecond(it.time).atZone(java.time.ZoneId.systemDefault()))
        }
        val label = buildString {
            append(place.name)
            if (time != null) append(" · pluie à $time")
            frame?.let { append(" · ").append(if (it.source == com.namm.meteo.domain.ForecastSource.AROME_PI) "AROME-PI" else "Open-Meteo") }
        }
        WidgetMapStore.write(applicationContext, bitmap, label)
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
