package fr.jbrunet.yama

import android.app.Application
import fr.jbrunet.yama.data.arome.AromePiSource
import fr.jbrunet.yama.data.local.ForecastCache
import fr.jbrunet.yama.data.local.SettingsStore
import fr.jbrunet.yama.data.location.LocationProvider
import fr.jbrunet.yama.data.remote.GeocodingApi
import fr.jbrunet.yama.data.remote.GitHubApi
import fr.jbrunet.yama.data.remote.Network
import fr.jbrunet.yama.data.remote.OpenMeteoApi
import fr.jbrunet.yama.data.remote.RainViewerApi
import fr.jbrunet.yama.data.repository.ForecastRepository
import fr.jbrunet.yama.data.repository.PlaceRepository
import fr.jbrunet.yama.data.repository.RainMapRepository
import fr.jbrunet.yama.data.update.UpdateController
import fr.jbrunet.yama.data.update.UpdateManager
import fr.jbrunet.yama.work.Notifications
import fr.jbrunet.yama.work.RefreshWorker
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
    val satellite by lazy { fr.jbrunet.yama.data.satellite.SatelliteSource() }
    val rainMapRepository by lazy {
        RainMapRepository(openMeteo, RainViewerApi.create(), aromePi) { settings.current().aromeApiKey.ifBlank { null } }
    }
    val updateManager by lazy { UpdateManager(this, GitHubApi.create()) }

    /** État des mises à jour partagé par tous les écrans. */
    val updates by lazy { UpdateController(updateManager, settings) }

    override fun onCreate() {
        super.onCreate()
        Network.install(cacheDir)
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = Network.userAgent
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        Notifications.createChannels(this)
        RefreshWorker.schedule(this)
    }
}
