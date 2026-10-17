package com.namm.meteo.data

import com.namm.meteo.data.update.AvailableUpdate
import com.namm.meteo.data.update.UpdateController
import com.namm.meteo.domain.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateControllerTest {
    @Test
    fun checkIntervalIsThirtyMinutes() {
        // Garde-fou : la vérification au retour au premier plan ne doit pas spammer GitHub.
        assertEquals(30 * 60 * 1000L, UpdateController.CHECK_INTERVAL_MS)
        AvailableUpdate(AppVersion.parse("0.1.16")!!, "https://x/app.apk", null)
    }
}
