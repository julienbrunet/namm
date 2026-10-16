package com.namm.meteo.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.namm.meteo.MainActivity
import com.namm.meteo.MeteoApp
import com.namm.meteo.domain.WeatherCondition
import com.namm.meteo.domain.minutesUntilRain
import kotlin.math.roundToInt

/** Widget d'écran d'accueil : lit la dernière prévision en cache (rafraîchie par RefreshWorker). */
class WeatherWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val cached = (context.applicationContext as MeteoApp).forecastRepository.cached()
        provideContent {
            val primary = ColorProvider(Color(0xFFF2F3F5))
            val secondary = ColorProvider(Color(0xFF9AA0AC))
            Column(
                modifier = GlanceModifier.fillMaxSize()
                    .background(Color(0xFF353A45))
                    .cornerRadius(22.dp)
                    .padding(16.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (cached == null) {
                    Text("Ouvrez l'application", style = TextStyle(color = secondary))
                    return@Column
                }
                val c = cached.forecast.current
                Text(cached.place.name, style = TextStyle(color = secondary, fontSize = 12.sp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${c.temperature.roundToInt()}°",
                        style = TextStyle(color = primary, fontSize = 40.sp, fontWeight = FontWeight.Normal),
                    )
                    Spacer(GlanceModifier.width(12.dp))
                    Text(WeatherCondition.fromWmo(c.weatherCode).label, style = TextStyle(color = secondary))
                }
                Spacer(GlanceModifier.height(4.dp))
                val rainIn = minutesUntilRain(cached.forecast.nextPrecipitation)
                val raining = cached.forecast.nextPrecipitation.firstOrNull()?.let { it.millimeters >= 0.1 } == true
                Text(
                    when {
                        raining -> "Il pleut"
                        rainIn != null -> "Pluie dans $rainIn min"
                        else -> "Pas de pluie d'ici 2 h"
                    },
                    style = TextStyle(color = ColorProvider(Color(0xFF8FB3D9)), fontSize = 12.sp),
                )
            }
        }
    }
}

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = WeatherWidget()
}
