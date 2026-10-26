package com.namm.meteo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.captureRoboImage
import com.namm.meteo.Fixtures
import com.namm.meteo.data.local.Settings
import com.namm.meteo.data.update.AvailableUpdate
import com.namm.meteo.domain.AppVersion
import com.namm.meteo.domain.Place
import com.namm.meteo.ui.forecast.ForecastContent
import com.namm.meteo.data.update.UpdateUiState
import com.namm.meteo.ui.map.MapContent
import com.namm.meteo.ui.map.MapUiState
import com.namm.meteo.ui.settings.SearchState
import com.namm.meteo.ui.settings.SettingsContent
import com.namm.meteo.ui.theme.MeteoTheme
import com.namm.meteo.ui.theme.ThemeMode
import com.namm.meteo.ui.theme.ThemeStyle
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

    private fun capture(
        name: String,
        mode: ThemeMode = ThemeMode.DARK,
        style: ThemeStyle = ThemeStyle.ARDOISE,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        compose.setContent { MeteoTheme(mode, style, content) }
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
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun parametresMeteoFrance() = capture("parametres_meteo_france") {
        SettingsContent(
            settings = Settings(aromeApiKey = "abcdef0123456789"),
            search = SearchState(),
            versionName = "0.1.10",
            updateStatus = null,
            aromeStatus = "Clé valide ✓ · run du 16/10 23:00 · couche total precipitation rate",
        )
    }

    @Test
    fun accueilClair() = capture("accueil_clair", ThemeMode.LIGHT) { ForecastContent(Fixtures.localForecast()) }

    @Test
    fun accueilCiel() = capture("accueil_ciel", style = ThemeStyle.CIEL) { ForecastContent(Fixtures.localForecast()) }

    @Test
    fun accueilPastel() = capture("accueil_pastel", style = ThemeStyle.PASTEL) { ForecastContent(Fixtures.localForecast()) }

    @Test
    fun accueilAurore() = capture("accueil_aurore", style = ThemeStyle.AURORE) { ForecastContent(Fixtures.localForecast()) }

    /** Thème Nature : un paysage par ambiance (heure locale du lieu + code WMO). */
    private fun nature(name: String, time: String? = null, code: Int? = null) = capture(name, style = ThemeStyle.NATURE) {
        val data = Fixtures.localForecast()
        val current = data.forecast.current
        ForecastContent(
            data.copy(
                forecast = data.forecast.copy(
                    current = current.copy(time = time ?: current.time, weatherCode = code ?: current.weatherCode),
                ),
            ),
        )
    }

    @Test
    fun accueilNature() = nature("accueil_nature") // 19:45, pluie, après le coucher

    @Test
    fun natureJourSoleil() = nature("nature_jour_soleil", "2026-10-16T13:30", 0)

    @Test
    fun natureAubeBrume() = nature("nature_aube_brume", "2026-10-16T08:20", 45)

    @Test
    fun natureMatinEclaircies() = nature("nature_matin_eclaircies", "2026-10-16T10:15", 2)

    @Test
    fun natureCrepuscule() = nature("nature_crepuscule", "2026-10-16T18:45", 1)

    @Test
    fun natureNuitClaire() = nature("nature_nuit_claire", "2026-10-16T23:10", 0)

    @Test
    fun natureNeige() = nature("nature_neige", "2026-10-16T11:00", 73)

    @Test
    fun natureOrage() = nature("nature_orage", "2026-10-16T16:00", 95)

    @Test
    fun natureCouvert() = nature("nature_couvert", "2026-10-16T15:00", 3)

    /** Le paysage reste fixe : on fait défiler l'accueil jusqu'aux tuiles du bas. */
    @Test
    fun natureDefilement() {
        compose.setContent { MeteoTheme(ThemeMode.DARK, ThemeStyle.NATURE) { ForecastContent(Fixtures.localForecast()) } }
        compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp(startY = bottom * 0.9f, endY = top, durationMillis = 400) }
        compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp(startY = bottom * 0.9f, endY = top, durationMillis = 400) }
        compose.onRoot().captureRoboImage("screenshots/nature_defilement.png")
    }

    @Test
    fun parametresNature() = capture("parametres_nature", style = ThemeStyle.NATURE) {
        SettingsContent(
            settings = Settings(style = ThemeStyle.NATURE, savedPlaces = listOf(Place("Brest", 48.39, -4.49, "Bretagne, France"))),
            search = SearchState(),
            versionName = "0.1.14",
            updateStatus = null,
        )
    }

    @Test
    fun parametresAurore() = capture("parametres_aurore", style = ThemeStyle.AURORE) {
        SettingsContent(
            settings = Settings(style = ThemeStyle.AURORE, savedPlaces = listOf(Place("Brest", 48.39, -4.49, "Bretagne, France"))),
            search = SearchState(),
            versionName = "0.1.14",
            updateStatus = null,
        )
    }

    @Test
    fun parametresClair() = capture("parametres_clair", ThemeMode.LIGHT) {
        SettingsContent(
            settings = Settings(theme = ThemeMode.LIGHT, savedPlaces = listOf(Place("Brest", 48.39, -4.49, "Bretagne, France"))),
            search = SearchState(),
            versionName = "0.1.14",
            updateStatus = null,
        )
    }

    @Test
    fun carteClair() = capture("carte_clair", ThemeMode.LIGHT) {
        val map = Fixtures.rainMap()
        MapContent(MapUiState.Ready(Fixtures.paris, map, map.nowIndex + 3, loadedAt = 1_792_180_800_000L), online = false)
    }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun reglagesWidget() = capture("widget_reglages") {
        com.namm.meteo.widget.WidgetConfigContent(
            initial = com.namm.meteo.widget.WidgetStyle(com.namm.meteo.widget.WidgetOpacity.LOW, com.namm.meteo.widget.WidgetTextColor.LIGHT, hours = 12),
            isMap = false,
            onSave = {},
        )
    }

    @Test
    fun carte() = capture("carte") {
        val map = Fixtures.rainMap()
        MapContent(MapUiState.Ready(Fixtures.paris, map, map.nowIndex + 3, loadedAt = 1_792_180_800_000L), online = false)
    }

    @Test
    fun carteChargement() = capture("carte_chargement") {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(com.namm.meteo.ui.theme.Slate.Background),
            androidx.compose.ui.Alignment.Center,
        ) {
            com.namm.meteo.ui.map.LoadingSteps(com.namm.meteo.data.repository.LoadProgress("Prévision Météo-France", 9, 24))
        }
    }
}
