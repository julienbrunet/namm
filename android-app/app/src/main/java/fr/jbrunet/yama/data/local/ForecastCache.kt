package fr.jbrunet.yama.data.local

import fr.jbrunet.yama.data.remote.ForecastDto
import fr.jbrunet.yama.data.remote.Network
import fr.jbrunet.yama.domain.Place
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class CachedForecast(val place: Place, val fetchedAtMillis: Long, val forecast: ForecastDto)

/**
 * Last downloaded forecast, for offline mode and the widgets. Kept in memory as long as
 * the file has not changed: the 6 widgets don't re-read and re-parse the JSON.
 */
class ForecastCache(private val file: File) {

    @Volatile
    private var memo: Pair<Long, CachedForecast>? = null

    fun read(): CachedForecast? {
        val modified = file.lastModified() // 0 if the file does not exist
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

/** Replaces [target] with this file (atomic rename; copy if the rename fails). */
internal fun File.replace(target: File) {
    if (!renameTo(target)) {
        copyTo(target, overwrite = true)
        delete()
    }
}
