package fr.jbrunet.yama

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.DisposableEffect
import fr.jbrunet.yama.ui.theme.ThemeMode
import fr.jbrunet.yama.ui.theme.ThemeStyle
import fr.jbrunet.yama.ui.theme.screenBackground
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.jbrunet.yama.data.location.LocationProvider
import fr.jbrunet.yama.ui.forecast.ForecastScreen
import fr.jbrunet.yama.ui.forecast.ForecastUiState
import fr.jbrunet.yama.ui.forecast.ForecastViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.jbrunet.yama.ui.map.MapScreen
import fr.jbrunet.yama.ui.map.MapViewModel
import fr.jbrunet.yama.ui.settings.SettingsScreen
import fr.jbrunet.yama.ui.settings.SettingsViewModel
import fr.jbrunet.yama.ui.theme.MeteoTheme
import fr.jbrunet.yama.ui.theme.Slate

private enum class Screen { Forecast, Map, Settings }

class MainActivity : ComponentActivity() {

    /** Screen requested by a widget (“map” for the map widget). */
    private val requestedScreen = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        requestedScreen.value = intent.getStringExtra(EXTRA_SCREEN)
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
    }

    override fun onResume() {
        super.onResume()
        // Back to the foreground: new version published in the meantime? (at most every 30 min)
        (application as MeteoApp).updates.checkIfDue()
        // Widgets behind schedule (Android doze): refresh them as soon as the app is opened.
        fr.jbrunet.yama.widget.Widgets.refreshIfStale(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MeteoApp
        requestedScreen.value = intent?.getStringExtra(EXTRA_SCREEN)
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val mode = settings?.theme ?: ThemeMode.SYSTEM
            MeteoTheme(mode, settings?.style ?: ThemeStyle.DEFAULT) {
                // Light status bar icons on a dark background, dark ones on a light background.
                val dark = Slate.isDark
                DisposableEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    onDispose {}
                }
                var screen by rememberSaveable { mutableStateOf(Screen.Forecast) }
                val forecastViewModel: ForecastViewModel = viewModel(factory = ForecastViewModel.Factory)
                val forecastState by forecastViewModel.state.collectAsStateWithLifecycle()

                // By default the home screen shows the GPS position: ask for permission at launch.
                val permission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { forecastViewModel.refresh() }
                LaunchedEffect(Unit) {
                    if (app.settings.current().useLocation && !app.location.hasPermission()) {
                        permission.launch(LocationProvider.PERMISSIONS)
                    }
                }

                // Opened from a widget: go straight to the requested screen.
                val requested = requestedScreen.value
                LaunchedEffect(requested) {
                    when (requested) {
                        "map" -> screen = Screen.Map
                        "forecast" -> screen = Screen.Forecast
                    }
                    requestedScreen.value = null
                }

                BackHandler(enabled = screen != Screen.Forecast) { screen = Screen.Forecast }
                // Nature theme: the home landscape goes under the system bars.
                val immersive = screen == Screen.Forecast && Slate.Scenic
                Box(Modifier.fillMaxSize().screenBackground().then(if (immersive) Modifier else Modifier.safeDrawingPadding())) {
                    when (screen) {
                        Screen.Forecast -> ForecastScreen(
                            viewModel = forecastViewModel,
                            onOpenMap = { screen = Screen.Map },
                            onOpenSettings = { screen = Screen.Settings },
                        )
                        Screen.Map -> MapScreen(
                            viewModel = viewModel<MapViewModel>(factory = MapViewModel.Factory),
                            // Same place as the home screen: current position or the chosen saved place.
                            place = (forecastState as? ForecastUiState.Success)?.data?.place,
                            onBack = { screen = Screen.Forecast },
                        )
                        Screen.Settings -> SettingsScreen(
                            viewModel = viewModel<SettingsViewModel>(factory = SettingsViewModel.Factory),
                            onBack = { screen = Screen.Forecast },
                        )
                    }
                }
            }
        }
    }
}
