package fr.jbrunet.yama.data

import fr.jbrunet.yama.Fixtures
import fr.jbrunet.yama.data.remote.RadarDto
import fr.jbrunet.yama.data.remote.RadarFrameDto
import fr.jbrunet.yama.data.remote.RainViewerMapsDto
import fr.jbrunet.yama.data.repository.RainMapRepository
import fr.jbrunet.yama.data.repository.coordinates
import fr.jbrunet.yama.data.repository.forecastFrames
import fr.jbrunet.yama.data.repository.toFrames
import fr.jbrunet.yama.domain.ForecastGrid
import fr.jbrunet.yama.domain.MapFrame
import fr.jbrunet.yama.data.repository.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainMapTest {

    @Test
    fun `grid is centred on the place`() {
        val grid = RainMapRepository.gridAround(Fixtures.paris)
        val points = grid.coordinates()

        assertEquals(192, points.size)
        assertEquals(Fixtures.paris.latitude, (points.first().first + points.last().first) / 2, 1e-9)
        assertEquals(Fixtures.paris.longitude, (points.first().second + points.last().second) / 2, 1e-9)
    }

    @Test
    fun `forecast frames start at the current quarter hour`() {
        val grid = ForecastGrid(48.80, 2.30, 0.04, 0.06, 2, 2)
        val points = Fixtures.gridDto()
        val start = points.first().minutely15.time.first()

        val frames = forecastFrames(grid, points, now = start + 20 * 60)

        assertEquals(23, frames.size)
        assertEquals(start + 15 * 60, frames.first().time)
        assertEquals(points[3].minutely15.precipitation[1]!!.toFloat(), (frames.first() as MapFrame.Forecast)[1, 1])
    }

    @Test
    fun `grid follows the visible area and is reloaded only when needed`() {
        val view = Bounds(south = 48.0, west = 1.0, north = 50.0, east = 4.0)
        val grid = RainMapRepository.gridFor(view)

        assertTrue(grid.south < 48.0 && grid.west < 1.0)
        assertFalse(RainMapRepository.needsNewGrid(grid, view))
        // Small move within the margin: no reload.
        assertFalse(RainMapRepository.needsNewGrid(grid, Bounds(48.1, 1.1, 50.1, 4.1)))
        // Leaving the covered area, strong zoom out, strong zoom in: reload.
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
    fun `RainViewer radar tile url`() {
        val frames = RainViewerMapsDto("https://tilecache.rainviewer.com", RadarDto(listOf(RadarFrameDto(1, "/v2/radar/abc"))))
            .toFrames()

        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/abc/256/{z}/{x}/{y}/2/1_1.png",
            (frames.single() as MapFrame.Radar).tileUrlTemplate,
        )
    }
}

class RainMapRepositoryTest {

    private val radarOnly = object : fr.jbrunet.yama.data.remote.RainViewerApi {
        override suspend fun maps() = RainViewerMapsDto("https://h", RadarDto(listOf(RadarFrameDto(1, "/p"))))
    }

    private val failingOpenMeteo = java.lang.reflect.Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(fr.jbrunet.yama.data.remote.OpenMeteoApi::class.java),
    ) { _, _, _ -> throw java.io.IOException("HTTP 429") } as fr.jbrunet.yama.data.remote.OpenMeteoApi

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
            arrayOf(fr.jbrunet.yama.data.remote.OpenMeteoApi::class.java),
        ) { _, _, _ ->
            calls++
            val t = 1_000_000L
            List(RainMapRepository.GRID_COLS * RainMapRepository.GRID_ROWS) {
                fr.jbrunet.yama.data.remote.GridPointDto(0.0, 0.0, fr.jbrunet.yama.data.remote.GridSeriesDto(listOf(t, t + 900), listOf(1.0, 0.0)))
            }
        } as fr.jbrunet.yama.data.remote.OpenMeteoApi
        val radar = object : fr.jbrunet.yama.data.remote.RainViewerApi {
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
        val z = fr.jbrunet.yama.widget.WidgetMapRenderer.zoomFor(lat, 720)
        val b = fr.jbrunet.yama.widget.WidgetMapRenderer.bounds(lat, 3.08, 500, 720, z)
        val heightKm = (b.north - b.south) * 111
        assertTrue("hauteur $heightKm km", heightKm in 30.0..90.0)
        assertTrue(b.south < lat && b.north > lat && b.west < 3.08 && b.east > 3.08)
    }

    @Test
    fun `initial grid already covers the screen, no second load when the map appears`() {
        // Visible area computed before the map exists (screen size in dp, opening zoom), then the real area
        // reported by the map, slightly taller (system bars included).
        val initial = fr.jbrunet.yama.widget.WidgetMapRenderer.bounds(48.8566, 2.3522, 411, 891, fr.jbrunet.yama.ui.map.MapViewModel.INITIAL_ZOOM)
        val grid = RainMapRepository.gridFor(initial)
        val actual = fr.jbrunet.yama.widget.WidgetMapRenderer.bounds(48.8566, 2.3522, 411, 960, fr.jbrunet.yama.ui.map.MapViewModel.INITIAL_ZOOM)
        assertFalse(RainMapRepository.needsNewGrid(grid, actual))
        // The old fixed grid around the place did not cover the screen.
        assertTrue(RainMapRepository.needsNewGrid(RainMapRepository.gridAround(fr.jbrunet.yama.Fixtures.paris), actual))
    }
}
