package com.namm.meteo.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.util.LruCache
import com.namm.meteo.domain.CloudLayer
import com.namm.meteo.domain.ForecastGrid
import com.namm.meteo.domain.MapFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/** Fond de carte OpenStreetMap standard (sans clé, User-Agent = nom du paquet, cf. MeteoApp). */
val OsmTiles: OnlineTileSourceBase = TileSourceFactory.MAPNIK

/** Assombrit et désature les tuiles OSM pour coller au thème ardoise. */
val SlateMapFilter = ColorMatrixColorFilter(
    ColorMatrix().apply {
        setSaturation(0.15f)
        postConcat(
            ColorMatrix(
                floatArrayOf(
                    -0.55f, 0f, 0f, 0f, 175f,
                    0f, -0.55f, 0f, 0f, 180f,
                    0f, 0f, -0.55f, 0f, 195f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    },
)

/**
 * Thèmes clairs : tuiles OSM en gris, éclaircies vers un blanc cassé (55 %), pour que la pluie
 * ressorte nettement sur un fond qui garde routes et noms de villes lisibles.
 */
val FadedMapFilter = ColorMatrixColorFilter(
    ColorMatrix().apply {
        setSaturation(0f)
        postConcat(
            ColorMatrix(
                floatArrayOf(
                    0.45f, 0f, 0f, 0f, 132f,
                    0f, 0.45f, 0f, 0f, 132.5f,
                    0f, 0f, 0.45f, 0f, 133.5f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    },
)

/** RainViewer ne sert plus de tuiles au-delà du zoom 7 (image « zoom not supported »). */
const val RADAR_MAX_ZOOM = 7

/**
 * Radar RainViewer dessiné à la main : tuiles au zoom min(carte, 7), étirées au zoom courant.
 * (Un TilesOverlay osmdroid demanderait des tuiles z ≥ 8 que RainViewer refuse.)
 */
class RadarOverlay(private val view: MapView) : Overlay() {
    var frame: MapFrame.Radar? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pending = mutableSetOf<String>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 210 }
    private val a = Point()
    private val b = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val f = frame ?: return
        val z = min(RADAR_MAX_ZOOM, floor(mapView.zoomLevelDouble).toInt()).coerceAtLeast(0)
        val box = mapView.boundingBox
        val n = 1 shl z
        val x0 = lonToTile(box.lonWest, z).coerceIn(0, n - 1)
        val x1 = lonToTile(box.lonEast, z).coerceIn(0, n - 1)
        val y0 = latToTile(box.latNorth, z).coerceIn(0, n - 1)
        val y1 = latToTile(box.latSouth, z).coerceIn(0, n - 1)
        val projection = mapView.projection
        for (x in x0..x1) for (y in y0..y1) {
            val url = f.tileUrlTemplate.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
            val bmp = RadarTileCache.get(url) ?: run { load(url); null } ?: continue
            projection.toPixels(GeoPoint(tileToLat(y, z), tileToLon(x, z)), a)
            projection.toPixels(GeoPoint(tileToLat(y + 1, z), tileToLon(x + 1, z)), b)
            canvas.drawBitmap(bmp, null, RectF(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat()), paint)
        }
    }

    private fun load(url: String) {
        if (!pending.add(url)) return
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { RadarTileCache.fetch(url) }
            pending.remove(url)
            if (bmp != null) view.invalidate()
        }
    }

    override fun onDetach(mapView: MapView) {
        scope.cancel()
    }
}

/** Cache mémoire des tuiles radar (partagé entre les images de l'animation). */
object RadarTileCache {
    private val cache = LruCache<String, Bitmap>(160)
    private val client = OkHttpClient()

    fun get(url: String): Bitmap? = cache.get(url)

    fun fetch(url: String): Bitmap? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            BitmapFactory.decodeStream(r.body?.byteStream())
        }
    }.getOrNull()?.also { cache.put(url, it) }
}

private fun lonToTile(lon: Double, z: Int) = floor((lon + 180) / 360 * (1 shl z)).toInt()
private fun latToTile(lat: Double, z: Int): Int {
    val r = Math.toRadians(lat)
    return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)).toInt()
}
private fun tileToLon(x: Int, z: Int) = x.toDouble() / (1 shl z) * 360 - 180
private fun tileToLat(y: Int, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl z)))))

/**
 * Échelle d'intensité commune au radar (palette RainViewer « Universal Blue », n° 2)
 * et à la prévision : bleus pour la pluie faible à modérée, jaune → rouge pour la pluie forte.
 */
enum class RainLevel(val minMmPerHour: Double, val color: Int, val label: String) {
    VERY_LIGHT(0.2, 0x999BC4E2.toInt(), "faible"),
    LIGHT(1.0, 0xAA4A90D9.toInt(), ""),
    MODERATE(2.5, 0xBB1F5FBF.toInt(), "modérée"),
    HEAVY(5.0, 0xCCF2D13A.toInt(), "forte"),
    VERY_HEAVY(10.0, 0xCCF28C28.toInt(), ""),
    EXTREME(20.0, 0xDDE0412E.toInt(), "intense"),
}

/** Intensité (mm / 15 min) → couleur ARGB du palier, ou null sous le seuil de pluie. */
fun rainColor(mmPer15: Double): Int? {
    val mmPerHour = mmPer15 * 4
    return RainLevel.entries.lastOrNull { mmPerHour >= it.minMmPerHour }?.color
}

/**
 * Grille de prévision (Open-Meteo ou AROME-PI) : valeurs interpolées sur une image plus fine,
 * puis colorées par paliers → zones aux contours nets et arrondis.
 */
class ForecastGridOverlay : Overlay() {
    var frame: MapFrame.Forecast? = null
        set(value) {
            if (value !== field) bitmap = value?.let(ForecastBitmaps::get)
            field = value
        }
    private var bitmap: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false } // contours nets, pas de flou
    /** Teinte des nuages (blanc en sombre, gris ardoise sur le fond clair). */
    var cloudPaint = cloudPaint(dark = true)

    /** Couleur des pointillés de la zone de prévision (selon le thème). */
    var outlineColor: Int = 0x55F2F3F5
        set(value) {
            field = value
            outline.color = value
        }
    private val outline = Paint().apply {
        style = Paint.Style.STROKE
        color = 0x55F2F3F5
        strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }
    private val a = Point()
    private val b = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val f = frame ?: return
        val bmp = bitmap ?: return
        val grid = f.grid
        val projection = mapView.projection
        // Chaque point représente une cellule centrée sur lui : l'image couvre ± ½ pas.
        val north = grid.south + (grid.rows - 0.5) * grid.latStep
        val east = grid.west + (grid.cols - 0.5) * grid.lonStep
        projection.toPixels(GeoPoint(north, grid.west - grid.lonStep / 2), a)
        projection.toPixels(GeoPoint(grid.south - grid.latStep / 2, east), b)
        val rect = RectF(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat())
        // Nuages prévus sous la pluie.
        f.clouds?.let { c ->
            val cg = c.grid
            projection.toPixels(GeoPoint(cg.south + (cg.rows - 0.5) * cg.latStep, cg.west - cg.lonStep / 2), a)
            projection.toPixels(GeoPoint(cg.south - cg.latStep / 2, cg.west + (cg.cols - 0.5) * cg.lonStep), b)
            canvas.drawBitmap(ForecastBitmaps.clouds(c), null, RectF(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat()), cloudPaint)
        }
        canvas.drawBitmap(bmp, null, rect, paint)
        canvas.drawRect(rect, outline) // limite de la zone de prévision
    }
}

/**
 * Images de prévision déjà calculées (une par pas de temps) : préparées en arrière-plan par
 * [prepare] pour que l'affichage et l'animation ne calculent rien sur le thread principal.
 */
object ForecastBitmaps {
    private val cache = java.util.WeakHashMap<MapFrame.Forecast, Bitmap>()

    fun get(frame: MapFrame.Forecast): Bitmap =
        synchronized(cache) { cache[frame] } ?: renderForecast(frame.grid, frame.values).also { synchronized(cache) { cache[frame] = it } }

    private val clouds = java.util.WeakHashMap<CloudLayer, Bitmap>()

    fun clouds(layer: CloudLayer): Bitmap =
        synchronized(clouds) { clouds[layer] } ?: renderClouds(layer).also { synchronized(clouds) { clouds[layer] = it } }

    suspend fun prepare(frames: List<MapFrame>) = withContext(Dispatchers.Default) {
        frames.filterIsInstance<MapFrame.Forecast>().forEach { f -> get(f); f.clouds?.let(::clouds) }
    }
}

/** Taille max (px) de l'image de prévision : assez fine pour des bords nets, sans exploser la mémoire. */
private const val RENDER_MAX = 480

/**
 * Interpolation à noyau gaussien (voisinage 4 × 4) : contrairement au bilinéaire, un point
 * pluvieux isolé donne une tache ronde (et non un losange), et les contours sont arrondis.
 */
internal fun renderForecast(grid: ForecastGrid, values: List<List<Double>>): Bitmap =
    renderGrid(grid, values, 0.55) { rainColor(it) ?: 0 }

/** Voile nuageux : blanc dont l'opacité suit la couverture (rien sous 20 %, ≈ 70 % d'opacité à 100 %). */
internal fun renderClouds(layer: CloudLayer): Bitmap =
    renderGrid(layer.grid, layer.cover, 0.8) { cover -> (((cover - 20) / 80).coerceIn(0.0, 1.0) * 180).toInt() shl 24 or 0xFFFFFF }

private fun renderGrid(grid: ForecastGrid, values: List<List<Double>>, sigma: Double, color: (Double) -> Int): Bitmap {
    val k = (RENDER_MAX / maxOf(grid.cols, grid.rows)).coerceIn(1, 12)
    val w = grid.cols * k
    val h = grid.rows * k
    val pixels = IntArray(w * h)
    val sigma2 = 2 * sigma * sigma // en cellules²
    for (py in 0 until h) {
        // Ligne 0 de la grille = sud = bas de l'image ; centre de cellule à +0,5.
        val gy = (h - py - 0.5) / k - 0.5
        val r0 = floor(gy).toInt()
        for (px in 0 until w) {
            val gx = (px + 0.5) / k - 0.5
            val c0 = floor(gx).toInt()
            var sum = 0.0
            var weights = 0.0
            for (r in r0 - 1..r0 + 2) {
                if (r !in 0 until grid.rows) continue
                val dy = gy - r
                for (c in c0 - 1..c0 + 2) {
                    if (c !in 0 until grid.cols) continue
                    val dx = gx - c
                    val wgt = exp(-(dx * dx + dy * dy) / sigma2)
                    sum += wgt * values[r][c]
                    weights += wgt
                }
            }
            pixels[py * w + px] = color(if (weights > 0) sum / weights else 0.0)
        }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

/** Pinceau des nuages : garde l'opacité du voile, en blanc (thème sombre) ou gris ardoise (thème clair). */
fun cloudPaint(dark: Boolean) = Paint(Paint.FILTER_BITMAP_FLAG).apply {
    colorFilter = android.graphics.PorterDuffColorFilter(
        if (dark) 0xFFF2F3F5.toInt() else 0xFF5F6878.toInt(),
        android.graphics.PorterDuff.Mode.SRC_IN,
    )
}

/**
 * Nuages observés (satellite Meteosat) pour les images radar : un carré de ± 400 km autour
 * du lieu, téléchargé une fois par quart d'heure de prise de vue puis gardé en mémoire.
 */
class SatelliteOverlay(
    private val view: MapView,
    private val source: com.namm.meteo.data.satellite.SatelliteSource,
) : Overlay() {
    var time: Long? = null
    var box: com.namm.meteo.data.satellite.MercatorBox? = null
    var paint = cloudPaint(dark = true)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pending = mutableSetOf<String>()
    private val a = Point()
    private val b = Point()

    /** Télécharge à l'avance les images des heures données (animation fluide). */
    fun prefetch(times: List<Long>) {
        val bx = box ?: return
        times.map { com.namm.meteo.data.satellite.SatelliteSource.slot(it) }.distinct().forEach { load(bx, it) }
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val t = time ?: return
        val bx = box ?: return
        val bmp = source.cached(bx, t) ?: run { load(bx, com.namm.meteo.data.satellite.SatelliteSource.slot(t)); null } ?: return
        val projection = mapView.projection
        projection.toPixels(GeoPoint(mercLat(bx.maxY), mercLon(bx.minX)), a)
        projection.toPixels(GeoPoint(mercLat(bx.minY), mercLon(bx.maxX)), b)
        canvas.drawBitmap(bmp, null, RectF(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat()), paint)
    }

    private fun load(bx: com.namm.meteo.data.satellite.MercatorBox, slot: Long) {
        if (!pending.add("${bx.minX}_$slot")) return
        scope.launch {
            val bmp = source.clouds(bx, slot)
            pending.remove("${bx.minX}_$slot")
            if (bmp != null) view.invalidate()
        }
    }

    override fun onDetach(mapView: MapView) {
        scope.cancel()
    }

    private fun mercLon(x: Double) = x / com.namm.meteo.data.satellite.MercatorBox.HALF * 180
    private fun mercLat(y: Double) = Math.toDegrees(2 * atan(exp(y / com.namm.meteo.data.satellite.MercatorBox.HALF * PI)) - PI / 2)
}
