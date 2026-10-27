package fr.jbrunet.yama.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.captureRoboImage
import fr.jbrunet.yama.Fixtures
import fr.jbrunet.yama.R
import fr.jbrunet.yama.data.local.Settings
import fr.jbrunet.yama.data.update.AvailableUpdate
import fr.jbrunet.yama.domain.AppVersion
import fr.jbrunet.yama.domain.Place
import fr.jbrunet.yama.ui.forecast.ForecastContent
import fr.jbrunet.yama.data.update.UpdateUiState
import fr.jbrunet.yama.ui.map.MapContent
import fr.jbrunet.yama.ui.map.MapUiState
import fr.jbrunet.yama.ui.settings.SearchState
import fr.jbrunet.yama.ui.settings.SettingsContent
import fr.jbrunet.yama.ui.theme.MeteoTheme
import fr.jbrunet.yama.ui.theme.ThemeMode
import fr.jbrunet.yama.ui.theme.ThemeStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots rendered on the JVM (Robolectric): ./gradlew recordRoborazziDebug
 * English in screenshots/, French in screenshots/fr/ ([FrenchScreenshotTest]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Plain application: no WorkManager or network while capturing.
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
open class ScreenshotTest {

    protected open val dir = "screenshots"

    @get:Rule
    val compose = createComposeRule()

    private fun capture(
        name: String,
        mode: ThemeMode = ThemeMode.DARK,
        style: ThemeStyle = ThemeStyle.SLATE,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        compose.setContent { MeteoTheme(mode, style, content) }
        compose.onRoot().captureRoboImage("$dir/$name.png")
    }

    @Test
    fun home() = capture("home") { ForecastContent(Fixtures.localForecast()) }

    @Test
    @Config(qualifiers = "+h1500dp")
    fun homeFull() = capture("home_full") { ForecastContent(Fixtures.localForecast()) }

    @Test
    fun homeUpdateOffline() = capture("home_update_offline") {
        val update = AvailableUpdate(AppVersion.parse("0.2.0")!!, "", null)
        ForecastContent(Fixtures.localForecast(offline = true), UpdateUiState.Downloading(update, 0.6f))
    }

    @Test
    fun settings() = capture("settings") {
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
            updateStatus = UiText(R.string.update_latest),
        )
    }

    @Test
    fun settingsSearch() = capture("settings_search") {
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
    @Config(qualifiers = "+h1500dp")
    fun settingsMeteoFrance() = capture("settings_meteo_france") {
        SettingsContent(
            settings = Settings(aromeApiKey = "abcdef0123456789"),
            search = SearchState(),
            versionName = "0.1.10",
            updateStatus = null,
            aromeStatus = UiText(R.string.arome_key_valid, "16/10 23:00", "total precipitation rate"),
        )
    }

    @Test
    fun homeLight() = capture("home_light", ThemeMode.LIGHT) { ForecastContent(Fixtures.localForecast()) }

    @Test
    fun homeSky() = capture("home_sky", style = ThemeStyle.SKY) { ForecastContent(Fixtures.localForecast()) }

    @Test
    fun homePastel() = capture("home_pastel", style = ThemeStyle.PASTEL) { ForecastContent(Fixtures.localForecast()) }

    @Test
    fun homeAurora() = capture("home_aurora", style = ThemeStyle.AURORA) { ForecastContent(Fixtures.localForecast()) }

    /** Nature theme: one landscape per mood (local time of the place + WMO code). */
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
    fun homeNature() = nature("home_nature") // 19:45, rain, after sunset

    @Test
    fun natureSunnyDay() = nature("nature_sunny_day", "2026-10-16T13:30", 0)

    @Test
    fun natureMistyDawn() = nature("nature_misty_dawn", "2026-10-16T08:20", 45)

    @Test
    fun natureSunnySpells() = nature("nature_sunny_spells", "2026-10-16T10:15", 2)

    @Test
    fun natureDusk() = nature("nature_dusk", "2026-10-16T18:45", 1)

    @Test
    fun natureClearNight() = nature("nature_clear_night", "2026-10-16T23:10", 0)

    @Test
    fun natureSnow() = nature("nature_snow", "2026-10-16T11:00", 73)

    @Test
    fun natureStorm() = nature("nature_storm", "2026-10-16T16:00", 95)

    @Test
    fun natureOvercast() = nature("nature_overcast", "2026-10-16T15:00", 3)

    /** The landscape stays put: scroll the home screen down to the bottom tiles. */
    @Test
    fun natureScrolled() {
        compose.setContent { MeteoTheme(ThemeMode.DARK, ThemeStyle.NATURE) { ForecastContent(Fixtures.localForecast()) } }
        compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp(startY = bottom * 0.9f, endY = top, durationMillis = 400) }
        compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp(startY = bottom * 0.9f, endY = top, durationMillis = 400) }
        compose.onRoot().captureRoboImage("$dir/nature_scrolled.png")
    }

    @Test
    fun settingsNature() = capture("settings_nature", style = ThemeStyle.NATURE) {
        SettingsContent(
            settings = Settings(style = ThemeStyle.NATURE, savedPlaces = listOf(Place("Brest", 48.39, -4.49, "Bretagne, France"))),
            search = SearchState(),
            versionName = "0.1.14",
            updateStatus = null,
        )
    }

    @Test
    fun settingsAurora() = capture("settings_aurora", style = ThemeStyle.AURORA) {
        SettingsContent(
            settings = Settings(style = ThemeStyle.AURORA, savedPlaces = listOf(Place("Brest", 48.39, -4.49, "Bretagne, France"))),
            search = SearchState(),
            versionName = "0.1.14",
            updateStatus = null,
        )
    }

    @Test
    fun settingsLight() = capture("settings_light", ThemeMode.LIGHT) {
        SettingsContent(
            settings = Settings(theme = ThemeMode.LIGHT, savedPlaces = listOf(Place("Brest", 48.39, -4.49, "Bretagne, France"))),
            search = SearchState(),
            versionName = "0.1.14",
            updateStatus = null,
        )
    }

    @Test
    fun mapLight() = capture("map_light", ThemeMode.LIGHT) {
        val map = Fixtures.rainMap()
        MapContent(MapUiState.Ready(Fixtures.paris, map, map.nowIndex + 3, loadedAt = 1_792_180_800_000L), online = false)
    }

    @Test
    @Config(qualifiers = "+h1100dp")
    fun widgetSettings() = capture("widget_settings") {
        fr.jbrunet.yama.widget.WidgetConfigContent(
            initial = fr.jbrunet.yama.widget.WidgetStyle(fr.jbrunet.yama.widget.WidgetOpacity.LOW, fr.jbrunet.yama.widget.WidgetTextColor.LIGHT, hours = 12),
            isMap = false,
            onSave = {},
        )
    }

    @Test
    fun map() = capture("map") {
        val map = Fixtures.rainMap()
        MapContent(MapUiState.Ready(Fixtures.paris, map, map.nowIndex + 3, loadedAt = 1_792_180_800_000L), online = false)
    }

    @Test
    fun mapLoading() = capture("map_loading") {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(fr.jbrunet.yama.ui.theme.Slate.Background),
            androidx.compose.ui.Alignment.Center,
        ) {
            fr.jbrunet.yama.ui.map.LoadingSteps(fr.jbrunet.yama.data.repository.LoadProgress(fr.jbrunet.yama.data.repository.LoadStage.METEO_FRANCE, 9, 24))
        }
    }

    @Test
    fun mapWind() = capture("map_wind") {
        val map = Fixtures.rainMap()
        MapContent(
            MapUiState.Ready(Fixtures.paris, map, map.nowIndex + 3, loadedAt = 1_792_180_800_000L),
            online = false,
            wind = fr.jbrunet.yama.ui.map.WindUiState(visible = true, frames = Fixtures.wind(map.grid)),
        )
    }
}

/** Same screens in French. */
@Config(qualifiers = "+fr")
class FrenchScreenshotTest : ScreenshotTest() {
    override val dir = "screenshots/fr"
}
