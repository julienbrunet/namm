package fr.jbrunet.yama.work

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
import fr.jbrunet.yama.MeteoApp
import fr.jbrunet.yama.R
import fr.jbrunet.yama.data.repository.RainMapRepository
import fr.jbrunet.yama.data.satellite.MercatorBox
import fr.jbrunet.yama.domain.ForecastSource
import fr.jbrunet.yama.domain.Place
import fr.jbrunet.yama.domain.minutesUntilRain
import fr.jbrunet.yama.ui.theme.DarkSlate
import fr.jbrunet.yama.ui.theme.LightSlate
import fr.jbrunet.yama.widget.WidgetMapRenderer
import fr.jbrunet.yama.widget.WidgetMapStore
import fr.jbrunet.yama.widget.Widgets
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Every 15 min (Android minimum, may be delayed in doze): refreshes the forecast (cache + widgets), warns when rain
 * is coming within the hour, and checks once a day for a new version.
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
            // A single alert per rain episode: remember the announced start time.
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
     * Map widget image: current rain over ≈ 50 km around the place, in the widget's format.
     * With AROME-PI: on every run (15 min, 1-2 requests); without a key, Open-Meteo every 30 min.
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
        if (frame == null && !allowOpenMeteo) return // keep the previous image
        if (frame?.source == ForecastSource.OPEN_METEO) prefs.edit().putLong("last_open_meteo", nowMs).apply()

        val dark = settings.style.widgetDark(
            settings.theme,
            (applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
        )
        // Clouds observed by Meteosat over the exact extent of the widget.
        val b = WidgetMapRenderer.bounds(place.latitude, place.longitude, width, height, z)
        val box = MercatorBox(MercatorBox.x(b.west), MercatorBox.y(b.south), MercatorBox.x(b.east), MercatorBox.y(b.north))
        val scale = minOf(1.0, 640.0 / maxOf(width, height))
        val satellite = app.satellite.clouds(box, nowMs / 1000, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
        val tiles = WidgetMapRenderer.TileCache(File(applicationContext.cacheDir, "widget_tiles")).also { it.purge() }
        val bitmap = WidgetMapRenderer.render(
            width = width, height = height, lat = place.latitude, lon = place.longitude, z = z, frame = frame, dark = dark,
            background = (if (dark) DarkSlate else LightSlate).tile.toArgb(), satellite = satellite,
        ) { tz, x, y -> tiles.get(tz, x, y) }
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        val zone = ZoneId.systemDefault()
        val updated = fmt.format(Instant.ofEpochMilli(nowMs).atZone(zone))
        val label = if (frame != null) {
            applicationContext.getString(
                R.string.widget_map_caption,
                place.name,
                fmt.format(Instant.ofEpochSecond(frame.time).atZone(zone)),
                if (frame.source == ForecastSource.AROME_PI) "AROME-PI" else "Open-Meteo",
                updated,
            )
        } else {
            applicationContext.getString(R.string.widget_map_caption_no_rain, place.name, updated)
        }
        WidgetMapStore.write(applicationContext, bitmap, label)
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES) // Android minimum
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
