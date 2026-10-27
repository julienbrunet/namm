package fr.jbrunet.yama.data.satellite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/** Extent in Web Mercator (metres, EPSG:3857). */
data class MercatorBox(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    companion object {
        const val HALF = 20_037_508.34
        fun x(lon: Double) = lon * HALF / 180
        fun y(lat: Double) = ln(tan((90 + lat) * PI / 360)) / (PI / 180) * HALF / 180

        /** ± [halfKm] km square around a point (at Mercator scale). */
        fun around(lat: Double, lon: Double, halfKm: Double): MercatorBox {
            val k = 1 / kotlin.math.cos(Math.toRadians(lat)) // Mercator scale factor
            val h = halfKm * 1000 * k
            return MercatorBox(x(lon) - h, y(lat) - h, x(lon) + h, y(lat) + h)
        }
    }
}

/**
 * Clouds observed by the Meteosat satellite (10.8 µm infrared channel, one image / 15 min)
 * through EUMETSAT's free public WMS (no key). Cold cloud tops look bright: the image is
 * turned into a white veil whose opacity follows that brightness (ground = transparent).
 */
class SatelliteSource(private val client: OkHttpClient = fr.jbrunet.yama.data.remote.Network.uncachedClient) {
    private val cache = LruCache<String, Bitmap>(24)

    /** Image already downloaded (or null), without a network call. */
    fun cached(box: MercatorBox, time: Long): Bitmap? = cache.get(key(box, slot(time)))

    /** Cloud veil at [time] (unix s, rounded to the available quarter hour) over [box]. */
    suspend fun clouds(box: MercatorBox, time: Long, width: Int = 640, height: Int = 640): Bitmap? = withContext(Dispatchers.IO) {
        val t = slot(time)
        val k = key(box, t)
        cache.get(k)?.let { return@withContext it }
        val url = "$WMS?service=WMS&version=1.3.0&request=GetMap&layers=msg_fes:ir108&styles=" +
            "&crs=EPSG:3857&bbox=${f(box.minX)},${f(box.minY)},${f(box.maxX)},${f(box.maxY)}" +
            "&width=$width&height=$height&format=image/png&time=${ISO.format(Instant.ofEpochSecond(t))}"
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val bytes = r.body?.bytes() ?: return@use null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::toCloudVeil)
            }
        }.getOrNull()?.also { cache.put(k, it) }
    }

    companion object {
        const val WMS = "https://view.eumetsat.int/geoserver/wms"

        /** Delay before a Meteosat image is online after it was taken. */
        private const val LATENCY = 20 * 60L

        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
        private fun f(v: Double) = "%.0f".format(Locale.ROOT, v)
        private fun key(box: MercatorBox, t: Long) = "${box.minX.toLong()}_${box.minY.toLong()}_$t"

        /** Available imaging quarter hour closest to [time]. */
        fun slot(time: Long, now: Long = System.currentTimeMillis() / 1000): Long =
            minOf(time, now - LATENCY).let { it - Math.floorMod(it, 15 * 60L) }

        /** Infrared (grey levels) → white whose opacity grows with brightness (cold clouds). */
        fun toCloudVeil(src: Bitmap): Bitmap {
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) {
                val g = px[i] and 0xFF
                px[i] = (cloudAlpha(g) shl 24) or 0xFFFFFF
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }

        /** Infrared brightness (0-255) → veil opacity: the ground (≈ 40-70) stays transparent. */
        fun cloudAlpha(gray: Int): Int = (((gray - 75) / 110.0).coerceIn(0.0, 1.0) * 215).toInt()
    }
}
