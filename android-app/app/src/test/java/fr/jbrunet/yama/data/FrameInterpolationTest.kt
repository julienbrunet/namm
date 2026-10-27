package fr.jbrunet.yama.data

import fr.jbrunet.yama.domain.ForecastGrid
import fr.jbrunet.yama.domain.MapFrame
import fr.jbrunet.yama.ui.map.FrameInterpolation
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameInterpolationTest {
    private val grid = ForecastGrid(45.0, 3.0, 0.1, 0.1, rows = 16, cols = 12)

    /** Une averse (un seul point de grille) en (r, c). */
    private fun shower(time: Long, r: Int, c: Int) =
        MapFrame.Forecast(time, FloatArray(grid.rows * grid.cols) { if (it == r * grid.cols + c) 4f else 0f }, grid)

    @Test
    fun motionFollowsTheShower() {
        val a = shower(0, 5, 3)
        val b = shower(900, 7, 5)
        assertEquals(2 to 2, FrameInterpolation.motion(a, b))
    }

    @Test
    fun halfwayFrameMovesTheShowerInsteadOfFading() {
        val a = shower(0, 5, 3)
        val b = shower(900, 7, 5)
        val mid = FrameInterpolation.between(a, b, 0.5f, FrameInterpolation.motion(a, b))
        assertEquals(450L, mid.time)
        // À mi-chemin, l'averse est entière au point intermédiaire (6, 4), pas à moitié à chaque bout.
        assertEquals(4f, mid[6, 4], 0.01f)
        assertEquals(0f, mid[5, 3], 0.01f)
        assertEquals(0f, mid[7, 5], 0.01f)
    }
}
