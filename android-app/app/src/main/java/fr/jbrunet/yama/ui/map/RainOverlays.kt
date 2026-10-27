package fr.jbrunet.yama.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.util.LruCache
import fr.jbrunet.yama.data.remote.Network
import fr.jbrunet.yama.domain.CloudLayer
import fr.jbrunet.yama.domain.ForecastGrid
import fr.jbrunet.yama.domain.MapFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** Standard OpenStreetMap background (no key, User-Agent = package name, see MeteoApp). */
val OsmTiles: OnlineTileSourceBase = TileSourceFactory.MAPNIK

/** Darkens and desaturates the OSM tiles to match the slate theme. */
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
 * Light themes: OSM tiles in grey, lightened towards an off-white (55 %), so that the rain
 * stands out clearly on a background that keeps roads and city names readable.
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

/** RainViewer no longer serves tiles beyond zoom 7 (“zoom not supported” image). */
const val RADAR_MAX_ZOOM = 7

/**
 * RainViewer radar drawn by hand: tiles at zoom min(map, 7), stretched to the current zoom.
 * (An osmdroid TilesOverlay would request z ≥ 8 tiles, which RainViewer refuses.)
 */
class RadarOverlay(private val view: MapView) : Overlay() {
    var frame: MapFrame.Radar? = null
    /** Next radar frame, cross-faded during the animation ([mix]: 0 = [frame], 1 = [next]). */
    var next: MapFrame.Radar? = null
    var mix: Float = 0f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pending = mutableSetOf<String>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 210 }
    private val a = Point()
    private val b = Point()
    // Reused on every draw (called for every animation and panning frame).
    private val geo = GeoPoint(0.0, 0.0)
    private val rect = RectF()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val f = frame ?: return
        val n2 = next
        if (n2 == null || mix <= 0f) {
            drawFrame(canvas, mapView, f, 210)
        } else {
            drawFrame(canvas, mapView, f, (210 * (1 - mix)).toInt())
            drawFrame(canvas, mapView, n2, (210 * mix).toInt())
        }
    }

    private fun drawFrame(canvas: Canvas, mapView: MapView, f: MapFrame.Radar, alpha: Int) {
        paint.alpha = alpha
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
            projection.toPixels(geo.apply { setCoords(tileToLat(y, z), tileToLon(x, z)) }, a)
            projection.toPixels(geo.apply { setCoords(tileToLat(y + 1, z), tileToLon(x + 1, z)) }, b)
            rect.set(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat())
            canvas.drawBitmap(bmp, null, rect, paint)
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

/**
 * In-memory cache of radar tiles (shared by the animation frames), limited in bytes
 * (1/8 of the app's memory) rather than in number of tiles; the HTTP disk cache of
 * [Network.client] avoids downloading them again from one map opening to the next.
 */
object RadarTileCache {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(url: String): Bitmap? = cache.get(url)

    fun fetch(url: String): Bitmap? = runCatching {
        Network.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
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
 * Intensity scale shared by the radar (RainViewer “Universal Blue” palette, no. 2)
 * and the forecast: blues for light to moderate rain, yellow → red for heavy rain.
 */
enum class RainLevel(val minMmPerHour: Double, val color: Int) {
    VERY_LIGHT(0.2, 0x999BC4E2.toInt()),
    LIGHT(1.0, 0xAA4A90D9.toInt()),
    MODERATE(2.5, 0xBB1F5FBF.toInt()),
    HEAVY(5.0, 0xCCF2D13A.toInt()),
    VERY_HEAVY(10.0, 0xCCF28C28.toInt()),
    EXTREME(20.0, 0xDDE0412E.toInt()),
}

/** Intensity (mm / 15 min) → ARGB colour of the level, or null below the rain threshold. */
fun rainColor(mmPer15: Double): Int? = rainArgb(mmPer15).takeIf { it != 0 }

/** Level thresholds in mm / 15 min, strongest first (avoids walking the enum for every pixel). */
private val RainThresholds = RainLevel.entries.reversed().map { it.minMmPerHour / 4 }.toDoubleArray()
private val RainColors = RainLevel.entries.reversed().map { it.color }.toIntArray()

/** Like [rainColor], 0 (transparent) below the rain threshold. */
private fun rainArgb(mmPer15: Double): Int {
    for (i in RainThresholds.indices) if (mmPer15 >= RainThresholds[i]) return RainColors[i]
    return 0
}

/**
 * Forecast grid (Open-Meteo or AROME-PI): values interpolated onto a finer image,
 * then coloured by level → areas with sharp, rounded edges.
 */
class ForecastGridOverlay : Overlay() {
    var frame: MapFrame.Forecast? = null
        set(value) {
            if (value !== field) bitmap = value?.let(ForecastBitmaps::get)
            field = value
        }
    private var bitmap: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false } // sharp edges, no blur
    /** Colour of the dotted outline of the forecast area (depends on the theme). */
    /** Cloud tint (white on dark, slate grey on the light background). */
    var cloudPaint = cloudPaint(dark = true)

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
        // Each point stands for a cell centred on it: the image covers ± ½ step.
        val north = grid.south + (grid.rows - 0.5) * grid.latStep
        val east = grid.west + (grid.cols - 0.5) * grid.lonStep
        projection.toPixels(GeoPoint(north, grid.west - grid.lonStep / 2), a)
        projection.toPixels(GeoPoint(grid.south - grid.latStep / 2, east), b)
        val rect = RectF(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat())
        // Forecast clouds under the rain.
        f.clouds?.let { c ->
            val cg = c.grid
            projection.toPixels(GeoPoint(cg.south + (cg.rows - 0.5) * cg.latStep, cg.west - cg.lonStep / 2), a)
            projection.toPixels(GeoPoint(cg.south - cg.latStep / 2, cg.west + (cg.cols - 0.5) * cg.lonStep), b)
            canvas.drawBitmap(ForecastBitmaps.clouds(c), null, RectF(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat()), cloudPaint)
        }
        canvas.drawBitmap(bmp, null, rect, paint)
        canvas.drawRect(rect, outline) // edge of the forecast area
    }
}

/**
 * Forecast images already computed (one per time step): prepared in the background by
 * [prepare] so that display and animation compute nothing on the main thread.
 */
object ForecastBitmaps {
    // Keys compared by identity (MapFrame.Forecast is not a data class): O(1) lookup.
    private val cache = java.util.WeakHashMap<MapFrame.Forecast, Bitmap>()

    fun get(frame: MapFrame.Forecast): Bitmap =
        synchronized(cache) { cache[frame] } ?: renderForecast(frame).also { synchronized(cache) { cache[frame] = it } }

    private val clouds = java.util.WeakHashMap<CloudLayer, Bitmap>()

    fun clouds(layer: CloudLayer): Bitmap =
        synchronized(clouds) { clouds[layer] } ?: renderClouds(layer).also { synchronized(clouds) { clouds[layer] = it } }

    suspend fun prepare(frames: List<MapFrame>) = withContext(Dispatchers.Default) {
        frames.filterIsInstance<MapFrame.Forecast>().forEach { f -> get(f); f.clouds?.let(::clouds) }
    }
}

/** Maximum size (px) of the forecast image: fine enough for sharp edges, without blowing up memory. */
private const val RENDER_MAX = 480

/**
 * Gaussian-kernel interpolation (4 × 4 neighbourhood): unlike bilinear, an isolated rainy
 * point gives a round blob (not a diamond), and edges are rounded.
 */
internal fun renderForecast(frame: MapFrame.Forecast): Bitmap {
    val (w, h) = forecastImageSize(frame.grid)
    return Bitmap.createBitmap(forecastPixels(frame), w, h, Bitmap.Config.ARGB_8888)
}

/** Size (px) of the forecast image: a whole multiple of the grid. */
internal fun forecastImageSize(grid: ForecastGrid): Pair<Int, Int> {
    val k = forecastScale(grid)
    return grid.cols * k to grid.rows * k
}

private fun forecastScale(grid: ForecastGrid) = (RENDER_MAX / maxOf(grid.cols, grid.rows)).coerceIn(1, 12)

/** ARGB pixels of the forecast image (row 0 = north). */
internal fun forecastPixels(frame: MapFrame.Forecast): IntArray {
    val grid = frame.grid
    val k = forecastScale(grid)
    val w = grid.cols * k
    val h = grid.rows * k
    // The gaussian kernel is separable: exp(-(dx² + dy²)/s) = exp(-dx²/s) · exp(-dy²/s).
    // The weights of the 4 neighbouring columns (resp. rows) depend only on px (resp. py):
    // computed once, only multiplications remain per pixel (no exp()).
    // Image: row 0 at the top = north, hence the vertical flip; cell centre at +0.5.
    val cols = neighbours(w, k, grid.cols, flip = false)
    val rows = neighbours(h, k, grid.rows, flip = true)
    val values = frame.values
    val pixels = IntArray(w * h)
    for (py in 0 until h) {
        for (px in 0 until w) {
            var sum = 0.0
            var weights = 0.0
            for (i in 0 until 4) {
                val wy = rows.weight[py * 4 + i]
                if (wy == 0.0) continue
                val base = rows.index[py * 4 + i] * grid.cols
                for (j in 0 until 4) {
                    val wgt = wy * cols.weight[px * 4 + j]
                    sum += wgt * values[base + cols.index[px * 4 + j]]
                    weights += wgt
                }
            }
            pixels[py * w + px] = rainArgb(if (weights > 0) sum / weights else 0.0)
        }
    }
    return pixels
}

/** For each pixel: index and gaussian weight of the 4 neighbouring cells (weight 0 outside the grid). */
private class Neighbours(val index: IntArray, val weight: DoubleArray)

private fun neighbours(pixels: Int, k: Int, cells: Int, flip: Boolean): Neighbours {
    val sigma2 = 2 * 0.55 * 0.55 // in cells²
    val index = IntArray(pixels * 4)
    val weight = DoubleArray(pixels * 4)
    for (p in 0 until pixels) {
        val g = ((if (flip) pixels - p else p + 1) - 0.5) / k - 0.5
        val c0 = floor(g).toInt()
        for (i in 0 until 4) {
            val c = c0 - 1 + i
            if (c !in 0 until cells) continue
            val d = g - c
            index[p * 4 + i] = c
            weight[p * 4 + i] = exp(-d * d / sigma2)
        }
    }
    return Neighbours(index, weight)
}

/**
 * Cloud veil (white, opacity from the cover: nothing under 20 %, ≈ 70 % at 100 %),
 * smoothed by a gaussian kernel on an image ≈ 8 times finer than the grid.
 */
internal fun renderClouds(layer: CloudLayer): Bitmap {
    val grid = layer.grid
    val k = (240 / maxOf(grid.cols, grid.rows)).coerceIn(1, 8)
    val w = grid.cols * k
    val h = grid.rows * k
    val pixels = IntArray(w * h)
    val sigma2 = 2 * 0.8 * 0.8
    for (py in 0 until h) {
        val gy = (h - py - 0.5) / k - 0.5 // row 0 = south = bottom of the image
        val r0 = floor(gy).toInt()
        for (px in 0 until w) {
            val gx = (px + 0.5) / k - 0.5
            val c0 = floor(gx).toInt()
            var sum = 0.0
            var weights = 0.0
            for (r in r0 - 1..r0 + 2) {
                if (r !in 0 until grid.rows) continue
                for (c in c0 - 1..c0 + 2) {
                    if (c !in 0 until grid.cols) continue
                    val wgt = exp(-((gx - c) * (gx - c) + (gy - r) * (gy - r)) / sigma2)
                    sum += wgt * layer[r, c]
                    weights += wgt
                }
            }
            val cover = if (weights > 0) sum / weights else 0.0
            pixels[py * w + px] = (((cover - 20) / 80).coerceIn(0.0, 1.0) * 180).toInt() shl 24 or 0xFFFFFF
        }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

/** Cloud paint: keeps the veil opacity, in white (dark theme) or slate grey (light theme). */
fun cloudPaint(dark: Boolean) = Paint(Paint.FILTER_BITMAP_FLAG).apply {
    colorFilter = android.graphics.PorterDuffColorFilter(
        if (dark) 0xFFF2F3F5.toInt() else 0xFF5F6878.toInt(),
        android.graphics.PorterDuff.Mode.SRC_IN,
    )
}

/**
 * Observed clouds (Meteosat satellite) for the radar frames: a ± 400 km square around
 * the place, downloaded once per imaging quarter hour, then kept in memory.
 */
class SatelliteOverlay(
    private val view: MapView,
    private val source: fr.jbrunet.yama.data.satellite.SatelliteSource,
) : Overlay() {
    var time: Long? = null
    var box: fr.jbrunet.yama.data.satellite.MercatorBox? = null
    var paint = cloudPaint(dark = true)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pending = mutableSetOf<String>()
    private val a = Point()
    private val b = Point()

    /** Downloads the images for the given times in advance (smooth animation). */
    fun prefetch(times: List<Long>) {
        val bx = box ?: return
        times.map { fr.jbrunet.yama.data.satellite.SatelliteSource.slot(it) }.distinct().forEach { load(bx, it) }
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val t = time ?: return
        val bx = box ?: return
        val bmp = source.cached(bx, t) ?: run { load(bx, fr.jbrunet.yama.data.satellite.SatelliteSource.slot(t)); null } ?: return
        val projection = mapView.projection
        projection.toPixels(GeoPoint(mercLat(bx.maxY), mercLon(bx.minX)), a)
        projection.toPixels(GeoPoint(mercLat(bx.minY), mercLon(bx.maxX)), b)
        canvas.drawBitmap(bmp, null, RectF(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat()), paint)
    }

    private fun load(bx: fr.jbrunet.yama.data.satellite.MercatorBox, slot: Long) {
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

    private fun mercLon(x: Double) = x / fr.jbrunet.yama.data.satellite.MercatorBox.HALF * 180
    private fun mercLat(y: Double) = Math.toDegrees(2 * atan(exp(y / fr.jbrunet.yama.data.satellite.MercatorBox.HALF * PI)) - PI / 2)
}

/** Wind speed levels (km/h) and arrow colours, shared by the map and the legend. */
enum class WindLevel(val minKmh: Float, val color: Int, val label: String) {
    CALM(0f, 0xFF9AA3B2.toInt(), "0"),
    LIGHT(15f, 0xFF6CC4B4.toInt(), "15"),
    MODERATE(30f, 0xFFE8C93A.toInt(), "30"),
    STRONG(50f, 0xFFF08A2C.toInt(), "50"),
    STORM(75f, 0xFFE0412E.toInt(), "75"),
}

fun windColor(kmh: Float): Int = WindLevel.entries.last { kmh >= it.minKmh }.color

/** Wind frame to show for [time]: the last one started (or the first one if earlier). */
fun List<fr.jbrunet.yama.domain.WindFrame>.at(time: Long): fr.jbrunet.yama.domain.WindFrame? =
    lastOrNull { it.time <= time } ?: firstOrNull()

/**
 * Wind: one arrow per grid point, pointing where the wind blows, longer and warmer
 * (grey → aqua → yellow → orange → red) as it strengthens.
 */
class WindOverlay(private val density: Float) : Overlay() {
    var frame: fr.jbrunet.yama.domain.WindFrame? = null
    /** Outline around the arrows so they stay readable over rain and clouds. */
    var haloColor: Int = 0x99353A45.toInt()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val p = Point()
    private val path = android.graphics.Path()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val f = frame ?: return
        val g = f.grid
        val projection = mapView.projection
        for (r in 0 until g.rows) for (c in 0 until g.cols) {
            val k = r * g.cols + c
            val speed = f.speed[k]
            projection.toPixels(GeoPoint(g.south + r * g.latStep, g.west + c * g.lonStep), p)
            // Direction “the wind comes from” → the arrow points where it goes.
            val rad = Math.toRadians(f.direction[k] + 180.0)
            val len = density * (12f + (speed.coerceAtMost(80f) / 80f) * 20f)
            val dx = (kotlin.math.sin(rad) * len / 2).toFloat()
            val dy = (-kotlin.math.cos(rad) * len / 2).toFloat()
            val x0 = p.x - dx
            val y0 = p.y - dy
            val x1 = p.x + dx
            val y1 = p.y + dy
            // Head: two strokes at ± 150° from the direction.
            val head = density * 6f
            path.reset()
            path.moveTo(x0, y0)
            path.lineTo(x1, y1)
            for (s in listOf(-1, 1)) {
                val a = rad + Math.toRadians(180.0 + s * 30)
                path.moveTo(x1, y1)
                path.lineTo(x1 + (kotlin.math.sin(a) * head).toFloat(), y1 - (kotlin.math.cos(a) * head).toFloat())
            }
            stroke.color = haloColor
            stroke.strokeWidth = density * 4.5f
            canvas.drawPath(path, stroke)
            stroke.color = windColor(speed)
            stroke.strokeWidth = density * 2.2f
            canvas.drawPath(path, stroke)
        }
    }
}
