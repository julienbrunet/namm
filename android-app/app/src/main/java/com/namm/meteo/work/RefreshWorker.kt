package com.namm.meteo.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.namm.meteo.MeteoApp
import com.namm.meteo.domain.minutesUntilRain
import com.namm.meteo.widget.WeatherWidget
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
        WeatherWidget().updateAll(applicationContext)

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
