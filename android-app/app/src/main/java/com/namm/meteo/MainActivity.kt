package com.namm.meteo

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.DisposableEffect
import com.namm.meteo.ui.theme.ThemeMode
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
import com.namm.meteo.data.location.LocationProvider
import com.namm.meteo.ui.forecast.ForecastScreen
import com.namm.meteo.ui.forecast.ForecastUiState
import com.namm.meteo.ui.forecast.ForecastViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.namm.meteo.ui.map.MapScreen
import com.namm.meteo.ui.map.MapViewModel
import com.namm.meteo.ui.settings.SettingsScreen
import com.namm.meteo.ui.settings.SettingsViewModel
import com.namm.meteo.ui.theme.MeteoTheme
import com.namm.meteo.ui.theme.Slate

private enum class Screen { Forecast, Map, Settings }

class MainActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        // Retour au premier plan : nouvelle version publiée entre-temps ? (au plus toutes les 30 min)
        (application as MeteoApp).updates.checkIfDue()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MeteoApp
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val mode = settings?.theme ?: ThemeMode.SYSTEM
            MeteoTheme(mode) {
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

                BackHandler(enabled = screen != Screen.Forecast) { screen = Screen.Forecast }
                Box(Modifier.fillMaxSize().background(Slate.Background).safeDrawingPadding()) {
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
