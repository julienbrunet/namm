package com.namm.meteo.data

import com.namm.meteo.Fixtures
import com.namm.meteo.data.remote.RadarDto
import com.namm.meteo.data.remote.RadarFrameDto
import com.namm.meteo.data.remote.RainViewerMapsDto
import com.namm.meteo.data.repository.RainMapRepository
import com.namm.meteo.data.repository.coordinates
import com.namm.meteo.data.repository.forecastFrames
import com.namm.meteo.data.repository.toFrames
import com.namm.meteo.domain.ForecastGrid
import com.namm.meteo.domain.MapFrame
import com.namm.meteo.data.repository.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainMapTest {

    @Test
    fun `la grille est centrée sur le lieu`() {
        val grid = RainMapRepository.gridAround(Fixtures.paris)
        val points = grid.coordinates()

        assertEquals(192, points.size)
        assertEquals(Fixtures.paris.latitude, (points.first().first + points.last().first) / 2, 1e-9)
        assertEquals(Fixtures.paris.longitude, (points.first().second + points.last().second) / 2, 1e-9)
    }

    @Test
    fun `les images de prévision commencent au quart d'heure en cours`() {
        val grid = ForecastGrid(48.80, 2.30, 0.04, 0.06, 2, 2)
        val points = Fixtures.gridDto()
        val start = points.first().minutely15.time.first()

        val frames = forecastFrames(grid, points, now = start + 20 * 60)

        assertEquals(23, frames.size)
        assertEquals(start + 15 * 60, frames.first().time)
        assertEquals(points[3].minutely15.precipitation[1], (frames.first() as MapFrame.Forecast).values[1][1])
    }

    @Test
    fun `la grille suit la zone visible et n'est rechargée que si nécessaire`() {
        val view = Bounds(south = 48.0, west = 1.0, north = 50.0, east = 4.0)
        val grid = RainMapRepository.gridFor(view)

        assertTrue(grid.south < 48.0 && grid.west < 1.0)
        assertFalse(RainMapRepository.needsNewGrid(grid, view))
        // Petit déplacement à l'intérieur de la marge : pas de rechargement.
        assertFalse(RainMapRepository.needsNewGrid(grid, Bounds(48.1, 1.1, 50.1, 4.1)))
        // Sortie de la zone couverte, dézoom fort, zoom fort : rechargement.
        assertTrue(RainMapRepository.needsNewGrid(grid, Bounds(49.0, 3.0, 51.0, 6.0)))
        assertTrue(RainMapRepository.needsNewGrid(grid, Bounds(46.0, -1.0, 52.0, 6.0)))
        assertTrue(RainMapRepository.needsNewGrid(grid, Bounds(48.9, 2.3, 49.2, 2.6)))
    }

    @Test
    fun coarseGridKeepsTheSameArea() {
        val grid = RainMapRepository.gridFor(Bounds(48.0, 1.0, 50.0, 4.0))
        val coarse = RainMapRepository.coarse(grid)
        val a = grid.coordinates()
        val b = coarse.coordinates()

        assertEquals(80, b.size)
        assertEquals(a.first().first, b.first().first, 1e-9)
        assertEquals(a.last().second, b.last().second, 1e-9)
    }

    @Test
    fun `url des tuiles radar RainViewer`() {
        val frames = RainViewerMapsDto("https://tilecache.rainviewer.com", RadarDto(listOf(RadarFrameDto(1, "/v2/radar/abc"))))
            .toFrames()

        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/abc/256/{z}/{x}/{y}/2/1_1.png",
            (frames.single() as MapFrame.Radar).tileUrlTemplate,
        )
    }
}

class RainMapRepositoryTest {

    private val radarOnly = object : com.namm.meteo.data.remote.RainViewerApi {
        override suspend fun maps() = RainViewerMapsDto("https://h", RadarDto(listOf(RadarFrameDto(1, "/p"))))
    }

    private val failingOpenMeteo = java.lang.reflect.Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(com.namm.meteo.data.remote.OpenMeteoApi::class.java),
    ) { _, _, _ -> throw java.io.IOException("HTTP 429") } as com.namm.meteo.data.remote.OpenMeteoApi

    @Test
    fun radarShownWhenForecastFails() = kotlinx.coroutines.runBlocking {
        val map = RainMapRepository(failingOpenMeteo, radarOnly).load(Fixtures.paris)

        assertEquals(1, map.frames.size)
    }
}

class RainMapCacheTest {

    @Test
    fun forecastIsReusedForAnAlreadySeenArea() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val api = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(com.namm.meteo.data.remote.OpenMeteoApi::class.java),
        ) { _, _, _ ->
            calls++
            val t = 1_000_000L
            List(RainMapRepository.GRID_COLS * RainMapRepository.GRID_ROWS) {
                com.namm.meteo.data.remote.GridPointDto(0.0, 0.0, com.namm.meteo.data.remote.GridSeriesDto(listOf(t, t + 900), listOf(1.0, 0.0)))
            }
        } as com.namm.meteo.data.remote.OpenMeteoApi
        val radar = object : com.namm.meteo.data.remote.RainViewerApi {
            override suspend fun maps() = RainViewerMapsDto("h", RadarDto(emptyList()))
        }
        val repo = RainMapRepository(api, radar)
        val view = Bounds(48.0, 1.0, 50.0, 4.0)

        repo.forecastFor(RainMapRepository.gridFor(view), now = 1_000_000L)

        assertEquals(1, calls)
        assertTrue(repo.cachedFor(Bounds(48.1, 1.1, 50.1, 4.1), now = 1_000_100L) != null)
        assertEquals(null, repo.cachedFor(view, now = 1_000_000L + RainMapRepository.CACHE_SECONDS + 1))
        assertEquals(null, repo.cachedFor(Bounds(40.0, -5.0, 42.0, -2.0), now = 1_000_100L))
    }
}

class WidgetMapGeometryTest {
    @Test
    fun widgetMapShowsAboutFiftyKilometres() {
        val lat = 45.78
        val z = com.namm.meteo.widget.WidgetMapRenderer.zoomFor(lat, 720)
        val b = com.namm.meteo.widget.WidgetMapRenderer.bounds(lat, 3.08, 500, 720, z)
        val heightKm = (b.north - b.south) * 111
        assertTrue("hauteur $heightKm km", heightKm in 30.0..90.0)
        assertTrue(b.south < lat && b.north > lat && b.west < 3.08 && b.east > 3.08)
    }
}
