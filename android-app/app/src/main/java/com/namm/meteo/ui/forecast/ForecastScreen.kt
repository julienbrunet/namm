package com.namm.meteo.ui.forecast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namm.meteo.domain.Forecast

@Composable
fun ForecastScreen(viewModel: ForecastViewModel = viewModel(factory = ForecastViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when (val s = state) {
                ForecastUiState.Loading -> CircularProgressIndicator()
                is ForecastUiState.Error -> Text("Erreur : ${s.message}")
                is ForecastUiState.Success -> ForecastContent(s.forecast)
            }
        }
    }
}

@Composable
private fun ForecastContent(forecast: Forecast) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("${forecast.current.temperature} °C", style = MaterialTheme.typography.displayLarge)
            Text("Vent ${forecast.current.windSpeed} km/h · Pluie ${forecast.current.precipitation} mm")
        }
        item { Text("Pluie dans les 2 h", style = MaterialTheme.typography.titleMedium) }
        items(forecast.nextPrecipitation) { step ->
            Text("${step.time.substringAfter('T')}  ${step.millimeters} mm")
        }
        item { Text("7 jours", style = MaterialTheme.typography.titleMedium) }
        items(forecast.days) { day ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(day.date)
                Text("${day.tempMin}° / ${day.tempMax}°")
                Text("${day.precipitationSum} mm")
            }
        }
    }
}
