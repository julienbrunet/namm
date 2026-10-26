package com.namm.meteo.data.local

import com.namm.meteo.data.remote.ForecastDto
import com.namm.meteo.data.remote.Network
import com.namm.meteo.domain.Place
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class CachedForecast(val place: Place, val fetchedAtMillis: Long, val forecast: ForecastDto)

/**
 * Dernière prévision téléchargée, pour le mode hors-ligne et le widget. Gardée en mémoire tant
 * que le fichier n'a pas changé : les 6 widgets ne relisent pas et ne reparsent pas le JSON.
 */
class ForecastCache(private val file: File) {

    @Volatile
    private var memo: Pair<Long, CachedForecast>? = null

    fun read(): CachedForecast? {
        val modified = file.lastModified() // 0 si le fichier n'existe pas
        if (modified == 0L) return null
        memo?.takeIf { it.first == modified }?.let { return it.second }
        return runCatching {
            Network.json.decodeFromString(CachedForecast.serializer(), file.readText())
        }.getOrNull()?.also { memo = modified to it }
    }

    fun write(value: CachedForecast) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(Network.json.encodeToString(CachedForecast.serializer(), value))
        tmp.replace(file)
        memo = file.lastModified() to value
    }
}

/** Remplace [target] par ce fichier (renommage atomique ; copie si le renommage échoue). */
internal fun File.replace(target: File) {
    if (!renameTo(target)) {
        copyTo(target, overwrite = true)
        delete()
    }
}
