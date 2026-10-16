package com.namm.meteo.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.namm.meteo.Fixtures
import com.namm.meteo.data.local.Settings
import com.namm.meteo.data.update.AvailableUpdate
import com.namm.meteo.domain.AppVersion
import com.namm.meteo.domain.Place
import com.namm.meteo.ui.forecast.ForecastContent
import com.namm.meteo.ui.forecast.UpdateUiState
import com.namm.meteo.ui.map.MapContent
import com.namm.meteo.ui.map.MapUiState
import com.namm.meteo.ui.settings.SearchState
import com.namm.meteo.ui.settings.SettingsContent
import com.namm.meteo.ui.theme.MeteoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Captures d'écran générées sur JVM (Robolectric) : ./gradlew recordRoborazziDebug */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Application neutre : pas de WorkManager ni de réseau pendant les captures.
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { MeteoTheme(content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test
    fun accueil() = capture("accueil") { ForecastContent(Fixtures.localForecast()) }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun pageComplete() = capture("page_complete") { ForecastContent(Fixtures.localForecast()) }

    @Test
    fun accueilMiseAJourHorsLigne() = capture("accueil_mise_a_jour") {
        val update = AvailableUpdate(AppVersion.parse("0.2.0")!!, "", null)
        ForecastContent(Fixtures.localForecast(offline = true), UpdateUiState.Downloading(update, 0.6f))
    }

    @Test
    fun parametres() = capture("parametres") {
        SettingsContent(
            settings = Settings(
                useLocation = true,
                savedPlaces = listOf(
                    Place("Clermont-Ferrand", 45.78, 3.09, "Auvergne-Rhône-Alpes, France"),
                    Place("Brest", 48.39, -4.49, "Bretagne, France"),
                ),
            ),
            search = SearchState(),
            versionName = "0.1.42",
            updateStatus = "Vous avez la dernière version",
        )
    }

    @Test
    fun parametresRecherche() = capture("parametres_recherche") {
        SettingsContent(
            settings = Settings(useLocation = false, place = Place("Brest", 48.39, -4.49), savedPlaces = listOf(Place("Brest", 48.39, -4.49, "Bretagne, France"))),
            search = SearchState(
                "Clermont",
                listOf(
                    Place("Clermont-Ferrand", 45.78, 3.09, "Auvergne-Rhône-Alpes, France"),
                    Place("Clermont", 49.38, 2.41, "Hauts-de-France, France"),
                ),
            ),
            versionName = "0.1.42",
            updateStatus = null,
        )
    }

    @Test
    fun carte() = capture("carte") {
        val map = Fixtures.rainMap()
        MapContent(MapUiState.Ready(Fixtures.paris, map, map.nowIndex + 3, loadedAt = 1_792_180_800_000L), online = false)
    }
}
