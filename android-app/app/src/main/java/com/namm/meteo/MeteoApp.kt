package com.namm.meteo

import android.app.Application
import com.namm.meteo.data.remote.OpenMeteoApi
import com.namm.meteo.data.repository.ForecastRepository

/** Injection de dépendances manuelle (suffisante au démarrage du projet). */
class MeteoApp : Application() {
    val forecastRepository by lazy { ForecastRepository(OpenMeteoApi.create()) }
}
