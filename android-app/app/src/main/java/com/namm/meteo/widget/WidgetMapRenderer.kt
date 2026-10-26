package com.namm.meteo.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.namm.meteo.domain.MapFrame
import com.namm.meteo.ui.map.FadedMapFilter
import com.namm.meteo.ui.map.SlateMapFilter
import com.namm.meteo.ui.map.renderForecast
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Dessine l'image du widget carte : fond OpenStreetMap (assombri en thème sombre),
 * prévision de pluie par-dessus et point de position. Projection Web Mercator.
 */
object WidgetMapRenderer {
    private const val TILE = 256

    /** Zoom qui montre ≈ [spanKm] km sur la hauteur de l'image. */
    fun zoomFor(lat: Double, heightPx: Int, spanKm: Double = 50.0): Int {
        val metersPerPxAtZ0 = 156_543.03 * kotlin.math.cos(Math.toRadians(lat))
        return kotlin.math.round(kotlin.math.log2(metersPerPxAtZ0 * heightPx / (spanKm * 1000))).toInt().coerceIn(6, 13)
    }

    /** Emprise géographique de l'image centrée sur (lat, lon). */
    fun bounds(lat: Double, lon: Double, width: Int, height: Int, z: Int): com.namm.meteo.data.repository.Bounds {
        val world = TILE * (1 shl z).toDouble()
        val cx = worldX(lon, z)
        val cy = worldY(lat, z)
        fun lonAt(x: Double) = x / world * 360 - 180
        fun latAt(y: Double) = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(PI * (1 - 2 * y / world))))
        return com.namm.meteo.data.repository.Bounds(
            south = latAt(cy + height / 2.0),
            west = lonAt(cx - width / 2.0),
            north = latAt(cy - height / 2.0),
            east = lonAt(cx + width / 2.0),
        )
    }

    private fun worldX(lon: Double, z: Int) = (lon + 180) / 360 * TILE * (1 shl z)
    private fun worldY(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat)
        return (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * TILE * (1 shl z)
    }

    fun render(
        width: Int,
        height: Int,
        lat: Double,
        lon: Double,
        z: Int,
        frame: MapFrame.Forecast?,
        dark: Boolean,
        background: Int,
        tile: (z: Int, x: Int, y: Int) -> Bitmap?,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(background)
        val cx = worldX(lon, z)
        val cy = worldY(lat, z)
        val left = cx - width / 2.0
        val top = cy - height / 2.0

        // Fond de carte
        val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = if (dark) SlateMapFilter else FadedMapFilter }
        for (tx in floor(left / TILE).toInt()..floor((left + width) / TILE).toInt()) {
            for (ty in floor(top / TILE).toInt()..floor((top + height) / TILE).toInt()) {
                val t = tile(z, tx, ty) ?: continue
                val x = (tx * TILE - left).toFloat()
                val y = (ty * TILE - top).toFloat()
                canvas.drawBitmap(t, null, RectF(x, y, x + TILE, y + TILE), tilePaint)
            }
        }

        // Pluie
        if (frame != null) {
            val g = frame.grid
            val rain = renderForecast(g, frame.values)
            val north = g.south + (g.rows - 0.5) * g.latStep
            val south = g.south - g.latStep / 2
            val west = g.west - g.lonStep / 2
            val east = g.west + (g.cols - 0.5) * g.lonStep
            canvas.drawBitmap(
                rain,
                null,
                RectF(
                    (worldX(west, z) - left).toFloat(),
                    (worldY(north, z) - top).toFloat(),
                    (worldX(east, z) - left).toFloat(),
                    (worldY(south, z) - top).toFloat(),
                ),
                Paint(),
            )
        }

        // Position
        val px = width / 2f
        val py = height / 2f
        canvas.drawCircle(px, py, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66F2F3F5 })
        canvas.drawCircle(px, py, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F3F5.toInt() })
        canvas.drawCircle(
            px, py, 6f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF353A45.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f },
        )
        return bmp
    }

    /** Tuiles OSM avec cache disque (30 jours) pour ménager les serveurs OpenStreetMap. */
    class TileCache(private val dir: File, private val client: OkHttpClient = OkHttpClient()) {
        fun get(z: Int, x: Int, y: Int): Bitmap? {
            val n = 1 shl z
            if (y !in 0 until n) return null
            val wx = ((x % n) + n) % n
            val file = File(dir, "${z}_${wx}_$y.png")
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < 30L * 24 * 3600 * 1000) {
                BitmapFactory.decodeFile(file.path)?.let { return it }
            }
            return runCatching {
                client.newCall(
                    Request.Builder().url("https://tile.openstreetmap.org/$z/$wx/$y.png")
                        .header("User-Agent", "NoAdMeteo-Android (widget)").build(),
                ).execute().use { r ->
                    if (!r.isSuccessful) return null
                    val bytes = r.body?.bytes() ?: return null
                    dir.mkdirs()
                    file.writeBytes(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }.getOrNull()
        }
    }
}
