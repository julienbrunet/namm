package fr.jbrunet.yama.ui.map

import fr.jbrunet.yama.domain.MapFrame
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Intermediate frames between two forecast steps (15 min) for a smooth animation.
 * Rather than a cross-fade (which makes showers “blink”), the overall rain motion between the
 * two frames is estimated, then each is slid towards the other before blending them.
 * Grids are small (≈ 200 Open-Meteo points, ≤ 160 × 160 AROME-PI): a few ms per frame.
 */
object FrameInterpolation {

    /** Rain motion (columns, rows) from [a] to [b], to the nearest grid point. */
    fun motion(a: MapFrame.Forecast, b: MapFrame.Forecast): Pair<Int, Int> {
        val g = a.grid
        val range = maxOf(2, maxOf(g.rows, g.cols) / 8)
        // Large grids are subsampled: the search stays around a million operations.
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
            // Slight preference for no motion (uniform rain, rain-free area).
            val score = sum / count * (1 + 0.02 * (dx * dx + dy * dy))
            if (score < bestScore) {
                bestScore = score
                best = dx to dy
            }
        }
        return best
    }

    /** Frame at fraction [t] (0 = [a], 1 = [b]) of the way, for a [motion] from a to b. */
    fun between(a: MapFrame.Forecast, b: MapFrame.Forecast, t: Float, motion: Pair<Int, Int>): MapFrame.Forecast {
        val g = a.grid
        val (mx, my) = motion
        val out = FloatArray(g.rows * g.cols)
        for (r in 0 until g.rows) for (c in 0 until g.cols) {
            // a moved forward by t·d, b moved back by (1 − t)·d: both meet at point (r, c).
            val va = sample(a.values, g.rows, g.cols, r - t * my, c - t * mx)
            val vb = sample(b.values, g.rows, g.cols, r + (1 - t) * my, c + (1 - t) * mx)
            out[r * g.cols + c] = va * (1 - t) + vb * t
        }
        val time = a.time + ((b.time - a.time) * t).roundToInt()
        return MapFrame.Forecast(time, out, g, a.source, if (t < 0.5f) a.clouds else b.clouds)
    }

    /** Bilinear lookup (0 outside the grid). */
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
