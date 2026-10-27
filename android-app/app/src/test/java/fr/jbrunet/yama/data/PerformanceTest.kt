package fr.jbrunet.yama.data

import fr.jbrunet.yama.Fixtures
import fr.jbrunet.yama.data.local.CachedForecast
import fr.jbrunet.yama.data.local.ForecastCache
import fr.jbrunet.yama.data.repository.roundCoordinate
import fr.jbrunet.yama.domain.MapFrame
import fr.jbrunet.yama.ui.forecast.SCENE_WIDTH
import fr.jbrunet.yama.ui.map.RainLevel
import fr.jbrunet.yama.ui.map.forecastImageSize
import fr.jbrunet.yama.ui.map.forecastPixels
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.exp
import kotlin.math.floor

// Robolectric: the palettes in RainOverlays.kt use Android graphics classes.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PerformanceTest {

    /** Former rendering (16 exp() per pixel), reference for the separable-kernel rendering. */
    private fun reference(frame: MapFrame.Forecast): IntArray {
        val grid = frame.grid
        val (w, h) = forecastImageSize(grid)
        val k = w / grid.cols
        val sigma2 = 2 * 0.55 * 0.55
        return IntArray(w * h) { i ->
            val px = i % w
            val py = i / w
            val gy = (h - py - 0.5) / k - 0.5
            val gx = (px + 0.5) / k - 0.5
            var sum = 0.0
            var weights = 0.0
            for (r in floor(gy).toInt() - 1..floor(gy).toInt() + 2) {
                if (r !in 0 until grid.rows) continue
                for (c in floor(gx).toInt() - 1..floor(gx).toInt() + 2) {
                    if (c !in 0 until grid.cols) continue
                    val wgt = exp(-((gx - c) * (gx - c) + (gy - r) * (gy - r)) / sigma2)
                    sum += wgt * frame[r, c]
                    weights += wgt
                }
            }
            val mmPerHour = (if (weights > 0) sum / weights else 0.0) * 4
            RainLevel.entries.lastOrNull { mmPerHour >= it.minMmPerHour }?.color ?: 0
        }
    }

    @Test
    fun `separable rendering gives the same pixels as the full gaussian kernel`() {
        val frames = Fixtures.rainMap().frames.filterIsInstance<MapFrame.Forecast>()
        for (frame in listOf(frames.first(), frames[40], frames.last())) {
            val expected = reference(frame)
            val actual = forecastPixels(frame)
            // Rounding may push a pixel sitting exactly on a threshold to the next level: tiny tolerance.
            val diff = expected.indices.count { expected[it] != actual[it] }
            assertEquals(0.0, diff.toDouble() / expected.size, 0.001)
        }
        assertArrayEquals(forecastPixels(frames.first()), forecastPixels(frames.first()))
    }

    @Test
    fun `forecast cache is read again only when the file changes`() {
        val file = File.createTempFile("forecast", ".json").apply { delete() }
        val cache = ForecastCache(file)
        assertNull(cache.read())
        cache.write(CachedForecast(Fixtures.paris, 1L, Fixtures.forecastDto()))
        val first = cache.read()
        assertSame(first, cache.read())
        // Another ForecastCache on the same file does read the content.
        assertEquals(1L, ForecastCache(file).read()?.fetchedAtMillis)
        file.delete()
    }

    @Test
    fun `position sent to Open-Meteo is rounded to two decimals`() {
        assertEquals(48.86, roundCoordinate(48.85661), 1e-9)
        assertEquals(-1.55, roundCoordinate(-1.5499), 1e-9)
    }

    @Test
    fun `Nature landscape renders fast`() {
        val sun = fr.jbrunet.yama.domain.sunPosition("2026-10-16T13:30", "2026-10-16T08:13", "2026-10-16T18:57")
        val renderer = fr.jbrunet.yama.ui.forecast.SceneRenderer(fr.jbrunet.yama.domain.WeatherCondition.RAIN, sun)
        renderer.render(SCENE_WIDTH, 650) // JIT warm-up
        val start = System.nanoTime()
        val pixels = renderer.render(SCENE_WIDTH, 650)
        val millis = (System.nanoTime() - start) / 1_000_000
        println("Paysage Nature : $millis ms")
        assertEquals(SCENE_WIDTH * 650, pixels.size)
        // Computed once per weather change; wide margin for CI machines.
        assert(millis < 2_000) { "paysage trop lent : $millis ms" }
    }
}
