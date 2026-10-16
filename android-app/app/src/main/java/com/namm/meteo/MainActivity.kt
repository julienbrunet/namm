package com.namm.meteo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.namm.meteo.ui.forecast.ForecastScreen
import com.namm.meteo.ui.theme.MeteoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeteoTheme { ForecastScreen() }
        }
    }
}
