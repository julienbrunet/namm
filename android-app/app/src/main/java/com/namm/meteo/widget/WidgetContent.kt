package com.namm.meteo.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.namm.meteo.R
import com.namm.meteo.data.repository.LocalForecast
import com.namm.meteo.domain.WeatherCondition
import com.namm.meteo.domain.minutesUntilRain
import com.namm.meteo.ui.theme.SlatePalette
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Couleurs d'un widget, issues de la palette ardoise (sombre ou claire). */
class WidgetColors(p: SlatePalette, backgroundAlpha: Float = 1f) {
    val background = p.background.copy(alpha = backgroundAlpha)
    val tile = p.tile
    val primary = ColorProvider(p.primary)
    val secondary = ColorProvider(p.secondary)
    val muted = ColorProvider(p.muted)
    val rain = ColorProvider(p.rain)
    val rainColor = p.rain
    val track = p.tileHighlight
}

fun WeatherCondition.widgetIcon(): Int = when (this) {
    WeatherCondition.CLEAR -> R.drawable.ic_w_sun
    WeatherCondition.PARTLY_CLOUDY -> R.drawable.ic_w_partly
    WeatherCondition.CLOUDY -> R.drawable.ic_w_cloud
    WeatherCondition.FOG -> R.drawable.ic_w_fog
    WeatherCondition.DRIZZLE -> R.drawable.ic_w_drizzle
    WeatherCondition.RAIN -> R.drawable.ic_w_rain
    WeatherCondition.SNOW -> R.drawable.ic_w_snow
    WeatherCondition.STORM -> R.drawable.ic_w_storm
}

@Composable
private fun Icon(res: Int, color: ColorProvider, size: Dp) =
    Image(ImageProvider(res), null, GlanceModifier.size(size), colorFilter = ColorFilter.tint(color))

/** Fond arrondi commun ; un appui ouvre l'app. */
@Composable
fun WidgetFrame(c: WidgetColors, onClick: Action, padding: Dp = 14.dp, content: @Composable () -> Unit) {
    Box(
        GlanceModifier.fillMaxSize().background(c.background).cornerRadius(22.dp).padding(padding).clickable(onClick),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

@Composable
fun EmptyContent(c: WidgetColors) =
    Text("Ouvrez l'application pour charger la météo", style = TextStyle(color = c.secondary, fontSize = 13.sp))

private fun rainText(data: LocalForecast): String {
    val steps = data.forecast.nextPrecipitation
    val minutes = minutesUntilRain(steps)
    return when {
        steps.firstOrNull()?.let { it.millimeters >= 0.1 } == true -> "Il pleut"
        minutes != null -> "Pluie dans $minutes min"
        else -> "Pas de pluie d'ici 2 h"
    }
}

/** Icône + température (+ lieu). */
@Composable
private fun CurrentBlock(data: LocalForecast, c: WidgetColors, big: Boolean) {
    val cur = data.forecast.current
    val condition = WeatherCondition.fromWmo(cur.weatherCode)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(condition.widgetIcon(), c.primary, if (big) 40.dp else 28.dp)
        Spacer(GlanceModifier.width(10.dp))
        Column {
            Text(
                "${cur.temperature.roundToInt()}°",
                style = TextStyle(color = c.primary, fontSize = if (big) 34.sp else 26.sp, fontWeight = FontWeight.Normal),
            )
            Text(data.place.name, style = TextStyle(color = c.secondary, fontSize = 12.sp), maxLines = 1)
        }
    }
}

/** Mini (2 × 1) : icône, température, lieu. */
@Composable
fun MiniContent(data: LocalForecast, c: WidgetColors) = CurrentBlock(data, c, big = false)

/** Pluie (4 × 1) : température + pluie dans les 2 h en barres. */
@Composable
fun RainContent(data: LocalForecast, c: WidgetColors) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CurrentBlock(data, c, big = false)
        Spacer(GlanceModifier.width(16.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(rainText(data), style = TextStyle(color = c.rain, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.height(6.dp))
            RainBars(data, c, maxHeight = 22)
        }
    }
}

@Composable
private fun RainBars(data: LocalForecast, c: WidgetColors, maxHeight: Int) {
    val steps = data.forecast.nextPrecipitation
    val max = (steps.maxOfOrNull { it.millimeters } ?: 0.0).coerceAtLeast(1.0)
    Row(GlanceModifier.fillMaxWidth().height(maxHeight.dp), verticalAlignment = Alignment.Bottom) {
        // Glance limite une ligne à 10 enfants : l'espacement est une marge interne, pas un Spacer.
        steps.take(10).forEach { step ->
            val h = ((step.millimeters / max) * maxHeight).roundToInt().coerceIn(3, maxHeight)
            Box(GlanceModifier.defaultWeight().padding(horizontal = 1.5.dp), contentAlignment = Alignment.BottomCenter) {
                Box(
                    GlanceModifier.fillMaxWidth().height(h.dp).cornerRadius(3.dp)
                        .background(if (step.millimeters > 0) c.rainColor else c.track),
                ) {}
            }
        }
    }
}

/** Journée (4 × 2) : météo actuelle + 6 prochaines heures. */
@Composable
fun DayContent(data: LocalForecast, c: WidgetColors) {
    Column(GlanceModifier.fillMaxSize()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CurrentBlock(data, c, big = true)
            Spacer(GlanceModifier.defaultWeight())
            Text(rainText(data), style = TextStyle(color = c.rain, fontSize = 12.sp))
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth()) {
            data.forecast.hours.take(6).forEach { h ->
                Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(h.time.substringAfter('T').take(2) + "h", style = TextStyle(color = c.muted, fontSize = 11.sp))
                    Spacer(GlanceModifier.height(2.dp))
                    Icon(WeatherCondition.fromWmo(h.weatherCode).widgetIcon(), c.primary, 20.dp)
                    Text("${h.temperature.roundToInt()}°", style = TextStyle(color = c.primary, fontSize = 13.sp))
                    Text(
                        "${h.precipitationProbability} %",
                        style = TextStyle(color = if (h.precipitationProbability >= 30) c.rain else c.muted, fontSize = 10.sp),
                    )
                }
            }
        }
    }
}

/** Semaine (4 × 3) : météo actuelle + 5 jours. */
@Composable
fun WeekContent(data: LocalForecast, c: WidgetColors) {
    Column(GlanceModifier.fillMaxSize()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CurrentBlock(data, c, big = true)
            Spacer(GlanceModifier.defaultWeight())
            Text(rainText(data), style = TextStyle(color = c.rain, fontSize = 12.sp))
        }
        Spacer(GlanceModifier.height(8.dp))
        data.forecast.days.take(5).forEach { d ->
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    LocalDate.parse(d.date).dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.FRENCH)
                        .replaceFirstChar { it.uppercase() },
                    style = TextStyle(color = c.primary, fontSize = 13.sp),
                    modifier = GlanceModifier.width(46.dp),
                )
                Icon(WeatherCondition.fromWmo(d.weatherCode).widgetIcon(), c.secondary, 18.dp)
                Spacer(GlanceModifier.defaultWeight())
                if (d.precipitationSum > 0) {
                    Text("%.1f mm".format(Locale.FRENCH, d.precipitationSum), style = TextStyle(color = c.rain, fontSize = 12.sp))
                    Spacer(GlanceModifier.width(10.dp))
                }
                Text("${d.tempMin.roundToInt()}°", style = TextStyle(color = c.secondary, fontSize = 13.sp))
                Spacer(GlanceModifier.width(8.dp))
                Text("${d.tempMax.roundToInt()}°", style = TextStyle(color = c.primary, fontSize = 13.sp))
            }
        }
    }
}

/** Carte de pluie : image pré-calculée (fond OSM + pluie) et légende. */
@Composable
fun MapContent(image: Bitmap?, label: String?, c: WidgetColors) {
    Column(GlanceModifier.fillMaxSize()) {
        Box(GlanceModifier.defaultWeight().fillMaxWidth().cornerRadius(16.dp).background(c.tile)) {
            if (image != null) {
                Image(ImageProvider(image), "Carte de pluie", GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(
                    "Carte en préparation…",
                    style = TextStyle(color = c.secondary, fontSize = 13.sp),
                    modifier = GlanceModifier.padding(12.dp),
                )
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(R.drawable.ic_w_drop, c.rain, 14.dp)
            Spacer(GlanceModifier.width(4.dp))
            Text(label ?: "Pluie", style = TextStyle(color = c.secondary, fontSize = 12.sp), maxLines = 1)
        }
    }
}

