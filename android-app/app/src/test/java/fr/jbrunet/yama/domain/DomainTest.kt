package fr.jbrunet.yama.domain

import fr.jbrunet.yama.data.remote.AssetDto
import fr.jbrunet.yama.data.remote.ReleaseDto
import fr.jbrunet.yama.data.update.toUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainTest {

    private fun v(s: String) = AppVersion.parse(s)!!

    @Test
    fun `comparaison numérique des versions`() {
        assertTrue(v("0.1.10") > v("0.1.9"))
        assertTrue(v("0.2.0") > v("0.1.99"))
        assertTrue(v("v1.0.0") > v("0.9.99"))
        assertEquals(0, v("0.2.0").compareTo(v("0.2")))
        assertEquals(0, v("YAMA 0.2.5").compareTo(v("0.2.5")))
    }

    @Test
    fun `détection de mise à jour depuis une release GitHub`() {
        val release = ReleaseDto("v0.2.0", assets = listOf(AssetDto("yama.apk", "https://x/yama.apk")))

        assertNotNull(release.toUpdate("0.1.99"))
        assertNull(release.toUpdate("0.2.0"))
        assertNull(release.toUpdate("0.2.1"))
        assertNull(release.copy(assets = emptyList()).toUpdate("0.1.0"))
    }

    @Test
    fun `alerte pluie imminente`() {
        fun steps(vararg mm: Double) = mm.mapIndexed { i, m -> PrecipitationStep("t$i", m) }

        assertEquals(30, minutesUntilRain(steps(0.0, 0.0, 0.4, 1.0)))
        assertNull(minutesUntilRain(steps(0.5, 0.0)))
        assertNull(minutesUntilRain(steps(0.0, 0.0)))
        assertNull(minutesUntilRain(emptyList()))
    }

    @Test
    fun `moment de la journée pour le décor`() {
        fun at(t: String) = sunPosition("2026-10-16T$t", "2026-10-16T08:13", "2026-10-16T18:57")

        assertEquals(DayPhase.NIGHT, at("06:30").phase)
        assertEquals(DayPhase.DAWN, at("07:40").phase)
        assertEquals(DayPhase.DAWN, at("08:50").phase)
        assertEquals(DayPhase.DAY, at("13:00").phase)
        assertEquals(DayPhase.DUSK, at("18:40").phase)
        assertEquals(DayPhase.NIGHT, at("19:45").phase)
        assertEquals(0f, at("08:13").progress, 0.001f)
        assertEquals(1f, at("18:57").progress, 0.001f)
        // Ancien cache sans lever ni coucher : 7 h – 19 h.
        assertEquals(DayPhase.DAY, sunPosition("2026-10-16T12:00", null, null).phase)
        assertEquals(DayPhase.NIGHT, sunPosition("2026-10-16T22:00", null, null).phase)
    }
}
