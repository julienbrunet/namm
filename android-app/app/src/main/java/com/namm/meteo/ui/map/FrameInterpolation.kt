package com.namm.meteo.ui.map

import com.namm.meteo.domain.MapFrame
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Images intermédiaires entre deux pas de prévision (15 min) pour une animation fluide.
 * Plutôt qu'un fondu (qui fait « clignoter » les averses), on estime le déplacement global
 * de la pluie entre les deux images puis on fait glisser chacune vers l'autre avant de les mélanger.
 * Les grilles sont petites (≈ 200 points Open-Meteo, ≤ 160 × 160 AROME-PI) : quelques ms par image.
 */
object FrameInterpolation {

    /** Déplacement (colonnes, lignes) de la pluie de [a] vers [b], au point de grille près. */
    fun motion(a: MapFrame.Forecast, b: MapFrame.Forecast): Pair<Int, Int> {
        val g = a.grid
        val range = maxOf(2, maxOf(g.rows, g.cols) / 8)
        // Sous-échantillonnage des grandes grilles : la recherche reste de l'ordre du million d'opérations.
        val stride = if (g.rows * g.cols > 4000) 2 else 1
        var best = 0 to 0
        var bestScore = Double.MAX_VALUE
        for (dy in -range..range) for (dx in -range..range) {
            var sum = 0.0
            var count = 0
            var r = maxOf(0, -dy)
            while (r < minOf(g.rows, g.rows - dy)) {
                var c = maxOf(0, -dx)
                while (c < minOf(g.cols, g.cols - dx)) {
                    val va = a.values[r * g.cols + c]
                    val vb = b.values[(r + dy) * g.cols + c + dx]
                    if (va > 0f || vb > 0f) {
                        val d = va - vb
                        sum += d * d
                    }
                    count++
                    c += stride
                }
                r += stride
            }
            if (count == 0) continue
            // Légère préférence pour l'immobilité (pluie uniforme, zone sans pluie).
            val score = sum / count * (1 + 0.02 * (dx * dx + dy * dy))
            if (score < bestScore) {
                bestScore = score
                best = dx to dy
            }
        }
        return best
    }

    /** Image à la fraction [t] (0 = [a], 1 = [b]) du chemin, pour un déplacement [motion] de a vers b. */
    fun between(a: MapFrame.Forecast, b: MapFrame.Forecast, t: Float, motion: Pair<Int, Int>): MapFrame.Forecast {
        val g = a.grid
        val (mx, my) = motion
        val out = FloatArray(g.rows * g.cols)
        for (r in 0 until g.rows) for (c in 0 until g.cols) {
            // a avancé de t·d, b reculé de (1 − t)·d : les deux coïncident au point (r, c).
            val va = sample(a.values, g.rows, g.cols, r - t * my, c - t * mx)
            val vb = sample(b.values, g.rows, g.cols, r + (1 - t) * my, c + (1 - t) * mx)
            out[r * g.cols + c] = va * (1 - t) + vb * t
        }
        val time = a.time + ((b.time - a.time) * t).roundToInt()
        return MapFrame.Forecast(time, out, g, a.source, if (t < 0.5f) a.clouds else b.clouds)
    }

    /** Lecture bilinéaire (0 hors de la grille). */
    private fun sample(v: FloatArray, rows: Int, cols: Int, r: Float, c: Float): Float {
        val r0 = floor(r).toInt()
        val c0 = floor(c).toInt()
        val fr = r - r0
        val fc = c - c0
        fun at(rr: Int, cc: Int) = if (rr in 0 until rows && cc in 0 until cols) v[rr * cols + cc] else 0f
        return at(r0, c0) * (1 - fr) * (1 - fc) + at(r0, c0 + 1) * (1 - fr) * fc +
            at(r0 + 1, c0) * fr * (1 - fc) + at(r0 + 1, c0 + 1) * fr * fc
    }
}
