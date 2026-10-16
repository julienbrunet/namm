package com.namm.meteo.data

import com.namm.meteo.Fixtures
import com.namm.meteo.data.repository.toDomain
import com.namm.meteo.data.repository.toPlaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastMappingTest {

    @Test
    fun `parse une vraie réponse Open-Meteo`() {
        val forecast = Fixtures.forecastDto().toDomain()

        assertEquals(7, forecast.days.size)
        assertEquals(8, forecast.nextPrecipitation.size)
        assertTrue(forecast.current.humidity in 0..100)
    }

    @Test
    fun `la prévision 2 h commence au quart d'heure courant`() {
        val forecast = Fixtures.forecastDto().toDomain()
        val now = forecast.current.time
        val first = forecast.nextPrecipitation.first().time

        assertEquals(now.take(13), first.take(13))
        assertTrue(first <= now)
        assertTrue(forecast.nextPrecipitation.zipWithNext().all { (a, b) -> a.time < b.time })
    }

    @Test
    fun `parse une vraie réponse de géocodage`() {
        val places = Fixtures.geocodingDto().toPlaces()

        assertEquals("Clermont-Ferrand", places.first().name)
        assertTrue(places.first().detail!!.contains("France"))
    }
}
