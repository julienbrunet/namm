package fr.jbrunet.yama.data

import fr.jbrunet.yama.data.update.AvailableUpdate
import fr.jbrunet.yama.data.update.UpdateController
import fr.jbrunet.yama.domain.AppVersion
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
