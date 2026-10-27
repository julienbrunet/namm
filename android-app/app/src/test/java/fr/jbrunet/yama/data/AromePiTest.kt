package fr.jbrunet.yama.data

import fr.jbrunet.yama.data.arome.AromePiSource
import fr.jbrunet.yama.data.arome.AromePiSource.Companion.toFrame
import fr.jbrunet.yama.data.arome.GeoTiff
import fr.jbrunet.yama.data.arome.PrecipKind
import fr.jbrunet.yama.domain.ForecastGrid
import fr.jbrunet.yama.domain.ForecastSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

class AromePiTest {

    /** Minimal little-endian float32 GeoTIFF (one band), like those from the Météo-France WCS. */
    private fun tiff(width: Int, height: Int, data: FloatArray, deflate: Boolean): ByteArray {
        val raw = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        data.forEach { raw.putFloat(it) }
        val pixels = if (!deflate) raw.array() else {
            val d = Deflater().apply { setInput(raw.array()); finish() }
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(1024)
            while (!d.finished()) out.write(chunk, 0, d.deflate(chunk))
            out.toByteArray()
        }
        val entries = 12
        val ifd = 8
        val extra = ifd + 2 + entries * 12 + 4
        val scaleOff = extra
        val tieOff = scaleOff + 24
        val dataOff = tieOff + 48
        val b = ByteBuffer.allocate(dataOff + pixels.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifd)
        b.position(ifd)
        b.putShort(entries.toShort())
        fun tag(id: Int, type: Int, count: Int, value: Int) {
            b.putShort(id.toShort()).putShort(type.toShort()).putInt(count)
            if (type == 3 && count == 1) b.putShort(value.toShort()).putShort(0) else b.putInt(value)
        }
        tag(256, 3, 1, width)
        tag(257, 3, 1, height)
        tag(258, 3, 1, 32)
        tag(259, 3, 1, if (deflate) 8 else 1)
        tag(262, 3, 1, 1)
        tag(273, 4, 1, dataOff)
        tag(277, 3, 1, 1)
        tag(278, 3, 1, height)
        tag(279, 4, 1, pixels.size)
        tag(339, 3, 1, 3)
        tag(33550, 12, 3, scaleOff)
        tag(33922, 12, 6, tieOff)
        b.putInt(0)
        b.position(scaleOff)
        b.putDouble(0.01).putDouble(0.01).putDouble(0.0)
        b.putDouble(0.0).putDouble(0.0).putDouble(0.0).putDouble(2.0).putDouble(46.0).putDouble(0.0)
        b.position(dataOff)
        b.put(pixels)
        return b.array()
    }

    @Test
    fun `an oversized compressed strip is cut to the expected size, a short strip is rejected`() {
        // 4 MB once decompressed, announced as a 3 × 2 image: only 24 bytes are read.
        val bomb = GeoTiff.decode(tiff(3, 2, FloatArray(1_000_000) { 1f }, deflate = true))
        assertEquals(1f, bomb[2, 1])
        val short = runCatching { GeoTiff.decode(tiff(3, 2, FloatArray(2), deflate = true)) }
        assertTrue(short.isFailure)
    }

    @Test
    fun decodesGeoTiffUncompressedAndDeflate() {
        val data = FloatArray(3 * 2) { it.toFloat() }
        for (deflate in listOf(false, true)) {
            val r = GeoTiff.decode(tiff(3, 2, data, deflate))
            assertEquals(3, r.width)
            assertEquals(2, r.height)
            assertEquals(2.0, r.west, 1e-9)
            assertEquals(46.0, r.north, 1e-9)
            assertEquals(5f, r[2, 1])
        }
    }

    @Test
    fun rasterIsFlippedSouthFirstAndConverted() {
        // Top row (north) = 0.002 mm/s → 1.8 mm/15 min; bottom row = 0.
        val r = GeoTiff.decode(tiff(2, 2, floatArrayOf(0.002f, 0.002f, 0f, 0f), deflate = false))
        val frame = r.toFrame(0, PrecipKind.RATE, null)

        assertEquals(ForecastSource.AROME_PI, frame.source)
        assertEquals(0.0, frame[0, 0].toDouble(), 1e-9) // south
        assertEquals(1.8, frame[1, 0].toDouble(), 1e-6) // north
        // Same layer expressed in mm/h: 8 mm/h → 2 mm/15 min.
        val mmPerHour = GeoTiff.decode(tiff(2, 2, floatArrayOf(8f, 0f, 0f, 0f), deflate = false)).toFrame(0, PrecipKind.RATE, null)
        assertEquals(2.0, mmPerHour[1, 0].toDouble(), 1e-6)
        assertEquals(46.0 - 1.5 * 0.01, frame.grid.south, 1e-9)
    }

    @Test
    fun choosesPrecipitationLayerAndLatestRun() {
        val xml = """
            <wcs:Capabilities><wcs:Contents>
            <wcs:CoverageSummary><wcs:CoverageId>TEMPERATURE__SPECIFIC_HEIGHT_LEVEL_ABOVE_GROUND___2026-10-16T21.00.00Z</wcs:CoverageId></wcs:CoverageSummary>
            <wcs:CoverageSummary><wcs:CoverageId>TOTAL_PRECIPITATION__GROUND_OR_WATER_SURFACE___2026-10-16T20.00.00Z</wcs:CoverageId></wcs:CoverageSummary>
            <wcs:CoverageSummary><wcs:CoverageId>TOTAL_PRECIPITATION__GROUND_OR_WATER_SURFACE___2026-10-16T21.00.00Z</wcs:CoverageId></wcs:CoverageSummary>
            <wcs:CoverageSummary><wcs:CoverageId>TOTAL_SNOW_PRECIPITATION__GROUND_OR_WATER_SURFACE___2026-10-16T22.00.00Z</wcs:CoverageId></wcs:CoverageSummary>
            <wcs:CoverageSummary><wcs:CoverageId>TOTAL_PRECIPITATION_RATE__GROUND_OR_WATER_SURFACE___2026-10-16T21.00.00Z</wcs:CoverageId></wcs:CoverageSummary>
            </wcs:Contents></wcs:Capabilities>
        """.trimIndent()

        val layer = AromePiSource.chooseLayer(xml)!!

        assertEquals(PrecipKind.RATE, layer.kind) // a rate is preferred to an accumulation since the run
        assertEquals("TOTAL_PRECIPITATION_RATE__GROUND_OR_WATER_SURFACE___2026-10-16T21.00.00Z", layer.coverageId)
        assertEquals(null, AromePiSource.chooseLayer("<x/>"))
    }

    @Test
    fun aromeUsedOnlyForFranceAndModerateZoom() {
        assertTrue(AromePiSource.covers(ForecastGrid(45.0, 2.0, 0.1, 0.1, 16, 12)))
        assertFalse(AromePiSource.covers(ForecastGrid(40.0, -80.0, 0.1, 0.1, 16, 12))) // outside France
        assertFalse(AromePiSource.covers(ForecastGrid(40.0, -5.0, 0.6, 0.8, 16, 12))) // view too wide
    }
}

class AromeCredentialsTest {
    @Test
    fun recognisesTheThreeKindsOfCredentials() {
        val appId = java.util.Base64.getEncoder().encodeToString("AbC123xyz:SeCrEt456".toByteArray())
        assertEquals(AromePiSource.Companion.CredentialType.APPLICATION_ID, AromePiSource.credentialType(appId))
        assertEquals(AromePiSource.Companion.CredentialType.JWT, AromePiSource.credentialType("eyJhbGciOi.eyJzdWIi.c2lnbmF0dXJl"))
        assertEquals(AromePiSource.Companion.CredentialType.API_KEY, AromePiSource.credentialType("eyJ4NXQiOiJ_simple_api_key"))
    }

    @Test
    fun extractsReadableErrorMessage() {
        assertEquals(
            "Invalid Credentials",
            AromePiSource.errorMessage("""{"code":"900901","message":"Invalid Credentials","description":"Invalid Credentials"}"""),
        )
    }
}

class AromeKeyNormalizationTest {
    @Test
    fun whitespaceInsidePastedKeyIsRemoved() {
        assertEquals("abcDEF123", AromePiSource.normalizeKey(" abc DEF\n123 \t"))
        val jwt = "eyJhbGciOi.\neyJzdWIi.c2ln bmF0dXJl"
        assertEquals(AromePiSource.Companion.CredentialType.JWT, AromePiSource.credentialType(jwt))
    }
}
