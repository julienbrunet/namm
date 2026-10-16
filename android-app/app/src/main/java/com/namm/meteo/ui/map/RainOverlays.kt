package com.namm.meteo.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import com.namm.meteo.domain.ForecastGrid
import com.namm.meteo.domain.MapFrame
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

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

/** Tuiles radar RainViewer d'une image donnée. */
class RadarTileSource(private val frame: MapFrame.Radar) :
    OnlineTileSourceBase("radar-${frame.time}", 0, 12, 256, ".png", arrayOf(""), "RainViewer") {
    override fun getTileURLString(index: Long): String = frame.tileUrlTemplate
        .replace("{z}", MapTileIndex.getZoom(index).toString())
        .replace("{x}", MapTileIndex.getX(index).toString())
        .replace("{y}", MapTileIndex.getY(index).toString())
}

/** Intensité (mm / 15 min) → couleur ARGB, du bleu léger au violet. */
fun rainColor(mmPer15: Double): Int? {
    val mmPerHour = mmPer15 * 4
    return when {
        mmPerHour < 0.2 -> null
        mmPerHour < 1 -> 0x668FB3D9
        mmPerHour < 2.5 -> 0x995B8FD0.toInt()
        mmPerHour < 7.5 -> 0xB33366CC.toInt()
        mmPerHour < 15 -> 0xCC7B4FC9.toInt()
        else -> 0xDDC04FB0.toInt()
    }
}

/**
 * Dessine la grille de prévision Open-Meteo : une image de rows × cols pixels
 * étirée sur l'emprise de la grille avec filtrage bilinéaire (rendu lissé façon radar).
 */
class ForecastGridOverlay(private val grid: ForecastGrid) : Overlay() {
    var frame: MapFrame.Forecast? = null
        set(value) {
            field = value
            bitmap = value?.let { render(it) }
        }
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val a = Point()
    private val b = Point()

    private fun render(frame: MapFrame.Forecast): Bitmap {
        val bmp = Bitmap.createBitmap(grid.cols, grid.rows, Bitmap.Config.ARGB_8888)
        for (r in 0 until grid.rows) for (c in 0 until grid.cols) {
            // Ligne 0 = sud → bas de l'image.
            bmp.setPixel(c, grid.rows - 1 - r, rainColor(frame.values[r][c]) ?: 0)
        }
        return bmp
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val bmp = bitmap ?: return
        val projection = mapView.projection
        val north = grid.south + (grid.rows - 0.5) * grid.latStep
        val east = grid.west + (grid.cols - 0.5) * grid.lonStep
        projection.toPixels(GeoPoint(north, grid.west - grid.lonStep / 2), a)
        projection.toPixels(GeoPoint(grid.south - grid.latStep / 2, east), b)
        canvas.drawBitmap(bmp, null, RectF(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat()), paint)
    }
}
