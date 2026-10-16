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

/** Intensité (mm / 15 min) → couleur ARGB par paliers, du bleu léger au violet. */
fun rainColor(mmPer15: Double): Int? {
    val mmPerHour = mmPer15 * 4
    return when {
        mmPerHour < 0.2 -> null
        mmPerHour < 1 -> 0x888FB3D9.toInt()
        mmPerHour < 2.5 -> 0xAA5B8FD0.toInt()
        mmPerHour < 7.5 -> 0xBB3366CC.toInt()
        mmPerHour < 15 -> 0xCC7B4FC9.toInt()
        else -> 0xDDC04FB0.toInt()
    }
}

/**
 * Grille de prévision Open-Meteo : valeurs interpolées (bilinéaire) sur une image
 * [UPSCALE] fois plus fine, puis colorées par paliers → zones aux contours nets et arrondis.
 */
class ForecastGridOverlay(private val grid: ForecastGrid) : Overlay() {
    var frame: MapFrame.Forecast? = null
        set(value) {
            field = value
            bitmap = value?.let { renderForecast(grid, it.values) }
        }
    private var bitmap: Bitmap? = null
    private val paint = Paint() // pas de filtrage : on garde les contours nets
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
        val bmp = bitmap ?: return
        val projection = mapView.projection
        val north = grid.south + (grid.rows - 1) * grid.latStep
        val east = grid.west + (grid.cols - 1) * grid.lonStep
        projection.toPixels(GeoPoint(north, grid.west), a)
        projection.toPixels(GeoPoint(grid.south, east), b)
        val rect = RectF(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat())
        canvas.drawBitmap(bmp, null, rect, paint)
        canvas.drawRect(rect, outline) // limite de la zone de prévision
    }

    companion object {
        const val UPSCALE = 10
    }
}

/** Image (cols-1)·k × (rows-1)·k ; chaque pixel = interpolation bilinéaire des 4 points voisins. */
internal fun renderForecast(grid: ForecastGrid, values: List<List<Double>>): Bitmap {
    val k = ForecastGridOverlay.UPSCALE
    val w = (grid.cols - 1) * k
    val h = (grid.rows - 1) * k
    val pixels = IntArray(w * h)
    for (py in 0 until h) {
        // Ligne 0 de la grille = sud = bas de l'image.
        val gy = (h - 1 - py + 0.5) / k
        val r0 = floor(gy).toInt().coerceIn(0, grid.rows - 2)
        val ty = gy - r0
        for (px in 0 until w) {
            val gx = (px + 0.5) / k
            val c0 = floor(gx).toInt().coerceIn(0, grid.cols - 2)
            val tx = gx - c0
            val v = values[r0][c0] * (1 - tx) * (1 - ty) + values[r0][c0 + 1] * tx * (1 - ty) +
                values[r0 + 1][c0] * (1 - tx) * ty + values[r0 + 1][c0 + 1] * tx * ty
            pixels[py * w + px] = rainColor(v) ?: 0
        }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}
