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
            if (value !== field) bitmap = value?.let { renderForecast(it.grid, it.values) }
            field = value
        }
    private var bitmap: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false } // contours nets, pas de flou
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
        canvas.drawBitmap(bmp, null, rect, paint)
        canvas.drawRect(rect, outline) // limite de la zone de prévision
    }
}

/** Taille max (px) de l'image de prévision : assez fine pour des bords nets, sans exploser la mémoire. */
private const val RENDER_MAX = 480

/**
 * Interpolation à noyau gaussien (voisinage 4 × 4) : contrairement au bilinéaire, un point
 * pluvieux isolé donne une tache ronde (et non un losange), et les contours sont arrondis.
 */
internal fun renderForecast(grid: ForecastGrid, values: List<List<Double>>): Bitmap {
    val k = (RENDER_MAX / maxOf(grid.cols, grid.rows)).coerceIn(1, 12)
    val w = grid.cols * k
    val h = grid.rows * k
    val pixels = IntArray(w * h)
    val sigma2 = 2 * 0.55 * 0.55 // en cellules²
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
            pixels[py * w + px] = rainColor(if (weights > 0) sum / weights else 0.0) ?: 0
        }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}
