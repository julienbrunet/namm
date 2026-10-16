package com.namm.meteo.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.namm.meteo.Fixtures
import com.namm.meteo.data.repository.toDomain
import com.namm.meteo.ui.forecast.ForecastContent
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
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render() = compose.setContent {
        MeteoTheme { ForecastContent("Paris", Fixtures.forecastDto().toDomain()) }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
    fun accueil() {
        render()
        compose.onRoot().captureRoboImage("screenshots/accueil.png")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1500dp-xxhdpi")
    fun pageComplete() {
        render()
        compose.onRoot().captureRoboImage("screenshots/page_complete.png")
    }
}
