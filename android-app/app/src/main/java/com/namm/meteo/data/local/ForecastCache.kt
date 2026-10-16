package com.namm.meteo.data.local

import com.namm.meteo.data.remote.ForecastDto
import com.namm.meteo.data.remote.Network
import com.namm.meteo.domain.Place
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class CachedForecast(val place: Place, val fetchedAtMillis: Long, val forecast: ForecastDto)

/** Dernière prévision téléchargée, pour le mode hors-ligne et le widget. */
class ForecastCache(private val file: File) {

    fun read(): CachedForecast? = runCatching {
        Network.json.decodeFromString(CachedForecast.serializer(), file.readText())
    }.getOrNull()

    fun write(value: CachedForecast) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(Network.json.encodeToString(CachedForecast.serializer(), value))
        tmp.renameTo(file)
    }
}
