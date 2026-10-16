package com.namm.meteo.data.arome

import com.namm.meteo.domain.ForecastGrid
import com.namm.meteo.domain.ForecastSource
import com.namm.meteo.domain.MapFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/** Comment lire la couche de précipitation choisie. */
enum class PrecipKind {
    /** Taux en kg m⁻² s⁻¹ (= mm/s) → × 900 pour mm / 15 min. */
    RATE,

    /** Cumul sur les 15 dernières minutes (mm). */
    PER_15MIN,

    /** Cumul depuis le début du run (mm) → différence entre deux pas. */
    CUMULATIVE,
}

/** Couche AROME-PI retenue dans GetCapabilities. */
data class AromeLayer(val coverageId: String, val layer: String, val run: Instant, val kind: PrecipKind)

/** Clé refusée par Météo-France (401/403). */
class InvalidKeyException(message: String) : IOException(message)

/**
 * Prévision immédiate AROME-PI de Météo-France (maille 0,01°, pas de 15 min jusqu'à +6 h) via le
 * service WCS du portail https://portail-api.meteofrance.fr ; nécessite une clé d'API gratuite
 * (en-tête `apikey`). Couverture : France métropolitaine et alentours.
 */
class AromePiSource(private val client: OkHttpClient = OkHttpClient()) {

    private var cachedLayer: Pair<Long, AromeLayer>? = null

    /** Découvre la couche de précipitation et le run le plus récent (mémorisé 5 min). */
    suspend fun latestLayer(key: String, fresh: Boolean = false): AromeLayer {
        val now = System.currentTimeMillis()
        cachedLayer?.takeIf { !fresh && now - it.first < 5 * 60_000 }?.let { return it.second }
        val layer = chooseLayer(get(key, "$BASE/GetCapabilities?service=WCS&version=2.0.1&language=fre").decodeToString())
            ?: throw IOException("Aucune couche de précipitation trouvée dans AROME-PI")
        cachedLayer = now to layer
        return layer
    }

    /** Images de prévision (≤ 6 h) sur l'emprise de [area], à partir du quart d'heure en cours. */
    suspend fun forecast(key: String, area: ForecastGrid, now: Long = System.currentTimeMillis() / 1000): List<MapFrame> =
        coroutineScope {
            val layer = latestLayer(key)
            val runSec = layer.run.epochSecond
            val steps = (1..MAX_STEPS).map { runSec + it * STEP }.filter { it + STEP > now }
            if (steps.isEmpty()) return@coroutineScope emptyList()
            // Couche cumulée : il faut aussi le pas précédent pour faire la différence.
            val needed = if (layer.kind == PrecipKind.CUMULATIVE) listOf(steps.first() - STEP) + steps else steps
            val limiter = Semaphore(4)
            val rasters = needed.map { t ->
                async {
                    limiter.withPermit {
                        if (t <= runSec) null else downsample(GeoTiff.decode(coverage(key, layer.coverageId, area, t)))
                    }
                }
            }.awaitAll()

            steps.mapIndexed { i, t ->
                val index = if (layer.kind == PrecipKind.CUMULATIVE) i + 1 else i
                val raster = rasters[index] ?: return@mapIndexed null
                val previous = if (layer.kind == PrecipKind.CUMULATIVE) rasters[index - 1] else null
                raster.toFrame(t, layer.kind, previous)
            }.filterNotNull()
        }

    private suspend fun coverage(key: String, id: String, area: ForecastGrid, time: Long): ByteArray {
        val north = area.south + area.latStep * (area.rows - 1)
        val east = area.west + area.lonStep * (area.cols - 1)
        val url = "$BASE/GetCoverage?service=WCS&version=2.0.1&coverageid=$id&format=image/tiff" +
            "&subset=time(${ISO.format(Instant.ofEpochSecond(time))})" +
            "&subset=lat(${f(max(area.south, FRANCE_SOUTH))},${f(minOf(north, FRANCE_NORTH))})" +
            "&subset=long(${f(max(area.west, FRANCE_WEST))},${f(minOf(east, FRANCE_EAST))})"
        return get(key, url)
    }

    private suspend fun get(key: String, url: String): ByteArray = withContext(Dispatchers.IO) {
        // Clé d'API (« API Key », durable) ou jeton OAuth (« Générer token », valable ~1 h).
        val request = Request.Builder().url(url).apply {
            if (key.startsWith("eyJ")) header("Authorization", "Bearer $key") else header("apikey", key)
        }.build()
        client.newCall(request).execute().use { r ->
            when {
                r.code == 401 || r.code == 403 -> throw InvalidKeyException("Clé refusée par Météo-France (${r.code})")
                !r.isSuccessful -> throw IOException("Météo-France : HTTP ${r.code}")
            }
            r.body?.bytes() ?: throw IOException("Réponse vide")
        }
    }

    companion object {
        const val BASE = "https://public-api.meteofrance.fr/public/aromepi/1.0/wcs/MF-NWP-HIGHRES-AROMEPI-001-FRANCE-WCS"
        const val STEP = 15 * 60L
        const val MAX_STEPS = 24 // +6 h

        // Domaine AROME-PI (approx.) : au-delà, on reste sur Open-Meteo.
        const val FRANCE_SOUTH = 37.5
        const val FRANCE_NORTH = 55.4
        const val FRANCE_WEST = -12.0
        const val FRANCE_EAST = 16.0

        /** Au-delà de cette hauteur de vue (°), les fichiers seraient trop lourds : Open-Meteo. */
        const val MAX_VIEW_HEIGHT = 4.0

        /** Taille max d'une image après réduction (px) : la mémoire reste raisonnable sur 24 images. */
        private const val MAX_SIZE = 160

        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
        private val RUN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH.mm.ss'Z'").withZone(ZoneOffset.UTC)
        private fun f(v: Double) = "%.3f".format(Locale.ROOT, v)

        fun covers(area: ForecastGrid): Boolean {
            val north = area.south + area.latStep * (area.rows - 1)
            val east = area.west + area.lonStep * (area.cols - 1)
            return north - area.south <= MAX_VIEW_HEIGHT &&
                area.south < FRANCE_NORTH && north > FRANCE_SOUTH && area.west < FRANCE_EAST && east > FRANCE_WEST
        }

        /**
         * Choisit, parmi les CoverageId « COUCHE___RUN » de GetCapabilities, une couche de
         * précipitation totale (de préférence par pas de 15 min) et son run le plus récent.
         */
        fun chooseLayer(capabilities: String): AromeLayer? {
            val ids = Regex("""<(?:\w+:)?CoverageId>\s*([^<\s]+)\s*<""").findAll(capabilities).map { it.groupValues[1] }
            val candidates = ids.mapNotNull { id ->
                val layer = id.substringBefore("___")
                val run = runCatching { Instant.from(RUN.parse(id.substringAfter("___").take(20))) }.getOrNull()
                if (run == null || !layer.contains("PRECIPITATION") || layer.contains("PROBABILITY")) return@mapNotNull null
                if (layer.contains("SNOW") || layer.contains("GRAUPEL") || layer.contains("HAIL")) return@mapNotNull null
                val kind = when {
                    layer.contains("RATE") -> PrecipKind.RATE
                    layer.contains("15MIN") || layer.contains("PT15M") -> PrecipKind.PER_15MIN
                    else -> PrecipKind.CUMULATIVE
                }
                AromeLayer(id, layer, run, kind)
            }.toList()
            if (candidates.isEmpty()) return null
            val preferred = candidates.filter { it.layer.startsWith("TOTAL_PRECIPITATION") }.ifEmpty { candidates }
            val byKind = listOf(PrecipKind.PER_15MIN, PrecipKind.RATE, PrecipKind.CUMULATIVE)
                .firstNotNullOf { k -> preferred.filter { it.kind == k }.takeIf { it.isNotEmpty() } }
            return byKind.maxBy { it.run }
        }

        /** Réduit l'image par moyenne de blocs pour ne pas dépasser [MAX_SIZE] px. */
        internal fun downsample(r: Raster): Raster {
            val factor = ceil(max(r.width, r.height) / MAX_SIZE.toDouble()).toInt()
            if (factor <= 1) return r
            val w = r.width / factor
            val h = r.height / factor
            val out = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0f
                for (dy in 0 until factor) for (dx in 0 until factor) sum += r[x * factor + dx, y * factor + dy]
                out[y * w + x] = sum / (factor * factor)
            }
            return Raster(w, h, r.west, r.north, r.resLon * factor, r.resLat * factor, out)
        }

        /** Raster (nord en haut) → image de prévision (ligne 0 = sud), en mm / 15 min. */
        internal fun Raster.toFrame(time: Long, kind: PrecipKind, previous: Raster?): MapFrame.Forecast {
            val grid = ForecastGrid(
                south = north - (height - 0.5) * resLat,
                west = west + resLon / 2,
                latStep = resLat,
                lonStep = resLon,
                rows = height,
                cols = width,
            )
            val values = List(height) { row ->
                val y = height - 1 - row
                List(width) { x ->
                    val v = this[x, y].toDouble()
                    when (kind) {
                        PrecipKind.RATE -> v * STEP
                        PrecipKind.PER_15MIN -> v
                        PrecipKind.CUMULATIVE -> (v - (previous?.takeIf { it.width == width && it.height == height }
                            ?.get(x, y)?.toDouble() ?: 0.0)).coerceAtLeast(0.0)
                    }
                }
            }
            return MapFrame.Forecast(time, values, grid, ForecastSource.AROME_PI)
        }
    }
}
