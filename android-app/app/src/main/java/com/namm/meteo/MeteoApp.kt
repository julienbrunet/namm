package com.namm.meteo

import android.app.Application
import com.namm.meteo.data.arome.AromePiSource
import com.namm.meteo.data.local.ForecastCache
import com.namm.meteo.data.local.SettingsStore
import com.namm.meteo.data.location.LocationProvider
import com.namm.meteo.data.remote.GeocodingApi
import com.namm.meteo.data.remote.GitHubApi
import com.namm.meteo.data.remote.OpenMeteoApi
import com.namm.meteo.data.remote.RainViewerApi
import com.namm.meteo.data.repository.ForecastRepository
import com.namm.meteo.data.repository.PlaceRepository
import com.namm.meteo.data.repository.RainMapRepository
import com.namm.meteo.data.update.UpdateManager
import com.namm.meteo.work.Notifications
import com.namm.meteo.work.RefreshWorker
import java.io.File

/** Injection de dépendances manuelle. */
class MeteoApp : Application() {

    private val openMeteo by lazy { OpenMeteoApi.create() }
    val settings by lazy { SettingsStore(this) }
    val location by lazy { LocationProvider(this) }
    val forecastRepository by lazy {
        ForecastRepository(openMeteo, ForecastCache(File(filesDir, "forecast.json")), settings, location)
    }
    val placeRepository by lazy { PlaceRepository(GeocodingApi.create()) }
    val aromePi by lazy { AromePiSource() }
    val rainMapRepository by lazy {
        RainMapRepository(openMeteo, RainViewerApi.create(), aromePi) { settings.current().aromeApiKey.ifBlank { null } }
    }
    val updateManager by lazy { UpdateManager(this, GitHubApi.create()) }

    override fun onCreate() {
        super.onCreate()
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        Notifications.createChannels(this)
        RefreshWorker.schedule(this)
    }
}
