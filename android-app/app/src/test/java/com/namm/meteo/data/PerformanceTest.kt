package com.namm.meteo.data

import com.namm.meteo.Fixtures
import com.namm.meteo.data.local.CachedForecast
import com.namm.meteo.data.local.ForecastCache
import com.namm.meteo.data.repository.roundCoordinate
import com.namm.meteo.domain.MapFrame
import com.namm.meteo.ui.map.RainLevel
import com.namm.meteo.ui.map.forecastImageSize
import com.namm.meteo.ui.map.forecastPixels
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

// Robolectric : les palettes de RainOverlays.kt utilisent des classes graphiques Android.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PerformanceTest {

    /** Ancien rendu (16 exp() par pixel), référence pour le rendu à noyau séparable. */
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
    fun `le rendu séparable donne les mêmes pixels que le noyau gaussien complet`() {
        val frames = Fixtures.rainMap().frames.filterIsInstance<MapFrame.Forecast>()
        for (frame in listOf(frames.first(), frames[40], frames.last())) {
            val expected = reference(frame)
            val actual = forecastPixels(frame)
            // Les arrondis peuvent faire basculer de palier un pixel pile sur un seuil : tolérance infime.
            val diff = expected.indices.count { expected[it] != actual[it] }
            assertEquals(0.0, diff.toDouble() / expected.size, 0.001)
        }
        assertArrayEquals(forecastPixels(frames.first()), forecastPixels(frames.first()))
    }

    @Test
    fun `le cache de prévision n'est relu que si le fichier change`() {
        val file = File.createTempFile("forecast", ".json").apply { delete() }
        val cache = ForecastCache(file)
        assertNull(cache.read())
        cache.write(CachedForecast(Fixtures.paris, 1L, Fixtures.forecastDto()))
        val first = cache.read()
        assertSame(first, cache.read())
        // Un autre ForecastCache sur le même fichier relit bien le contenu.
        assertEquals(1L, ForecastCache(file).read()?.fetchedAtMillis)
        file.delete()
    }

    @Test
    fun `la position envoyée à Open-Meteo est arrondie à 0,01°`() {
        assertEquals(48.86, roundCoordinate(48.85661), 1e-9)
        assertEquals(-1.55, roundCoordinate(-1.5499), 1e-9)
    }
}
