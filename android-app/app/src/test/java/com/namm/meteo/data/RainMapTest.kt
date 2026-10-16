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
import org.junit.Assert.assertEquals
import org.junit.Test

class RainMapTest {

    @Test
    fun `la grille est centrée sur le lieu`() {
        val grid = RainMapRepository.gridAround(Fixtures.paris)
        val points = grid.coordinates()

        assertEquals(121, points.size)
        assertEquals(Fixtures.paris.latitude, points[60].first, 1e-9)
        assertEquals(Fixtures.paris.longitude, points[60].second, 1e-9)
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
    fun `url des tuiles radar RainViewer`() {
        val frames = RainViewerMapsDto("https://tilecache.rainviewer.com", RadarDto(listOf(RadarFrameDto(1, "/v2/radar/abc"))))
            .toFrames()

        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/abc/256/{z}/{x}/{y}/2/1_1.png",
            (frames.single() as MapFrame.Radar).tileUrlTemplate,
        )
    }
}
