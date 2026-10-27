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

    /** Écran demandé par un widget (« map » pour le widget carte). */
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
        // Retour au premier plan : nouvelle version publiée entre-temps ? (au plus toutes les 30 min)
        (application as MeteoApp).updates.checkIfDue()
        // Widgets en retard (veille Android) : on les remet à jour dès que l'appli est ouverte.
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
                // Icônes de la barre d'état claires sur fond sombre, foncées sur fond clair.
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

                // Par défaut l'accueil affiche la position GPS : on demande l'autorisation au lancement.
                val permission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { forecastViewModel.refresh() }
                LaunchedEffect(Unit) {
                    if (app.settings.current().useLocation && !app.location.hasPermission()) {
                        permission.launch(LocationProvider.PERMISSIONS)
                    }
                }

                // Ouverture depuis un widget : on va directement à l'écran demandé.
                val requested = requestedScreen.value
                LaunchedEffect(requested) {
                    when (requested) {
                        "map" -> screen = Screen.Map
                        "forecast" -> screen = Screen.Forecast
                    }
                    requestedScreen.value = null
                }

                BackHandler(enabled = screen != Screen.Forecast) { screen = Screen.Forecast }
                // Thème Nature : le paysage de l'accueil passe sous les barres système.
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
                            // Même lieu que l'accueil : position actuelle ou lieu enregistré choisi.
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
