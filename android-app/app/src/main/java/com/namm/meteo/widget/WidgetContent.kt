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
import com.namm.meteo.ui.theme.DarkSlate
import com.namm.meteo.ui.theme.LightSlate
import com.namm.meteo.domain.HourlyForecast
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.glance.LocalContext
import androidx.glance.appwidget.AndroidRemoteViews
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Couleurs d'un widget : le fond suit le réglage du widget (Auto = thème de l'app, Clair, Sombre)
 * et le texte, en Auto, contraste avec ce fond.
 */
class WidgetColors(appPalette: SlatePalette, style: WidgetStyle = WidgetStyle()) {
    private val base = when (style.background) {
        WidgetBgColor.AUTO -> appPalette
        WidgetBgColor.LIGHT -> LightSlate
        WidgetBgColor.DARK -> DarkSlate
    }
    private val text = when (style.text) {
        WidgetTextColor.AUTO -> Triple(base.primary, base.secondary, base.muted)
        WidgetTextColor.LIGHT -> WidgetTextPalettes.light
        WidgetTextColor.DARK -> WidgetTextPalettes.dark
    }
    val background = base.background.copy(alpha = style.opacity.alpha)
    val tile = base.tile
    val primaryColor = text.first
    val secondaryColor = text.second
    val primary = ColorProvider(text.first)
    val secondary = ColorProvider(text.second)
    val muted = ColorProvider(text.third)
    val rainColor = when (style.text) {
        WidgetTextColor.AUTO -> base.rain
        WidgetTextColor.LIGHT -> WidgetTextPalettes.lightRain
        WidgetTextColor.DARK -> WidgetTextPalettes.darkRain
    }
    val rain = ColorProvider(rainColor)
    val track = if (style.opacity == WidgetOpacity.OPAQUE) base.tileHighlight else text.third.copy(alpha = 0.35f)
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

/** Fond arrondi commun ; un appui ouvre l'app. Contenu compact, centré verticalement. */
@Composable
fun WidgetFrame(
    c: WidgetColors,
    onClick: Action,
    padding: Dp = 14.dp,
    /** En haut par défaut (le contenu reste là où on a posé le widget) ; centré pour les widgets d'une ligne. */
    alignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit,
) {
    Box(
        GlanceModifier.fillMaxSize().background(c.background).cornerRadius(22.dp).padding(padding).clickable(onClick),
        contentAlignment = alignment,
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

/**
 * Date (petite) + heure (grande) toujours à jour : TextClock Android inséré dans le widget,
 * indépendant des rafraîchissements (toutes les 15 min).
 */
@Composable
private fun Clock(c: WidgetColors, timeSize: Float = 28f, width: Dp = 104.dp) {
    // Boîte de largeur fixe alignée à droite : une vue Android insérée (AndroidRemoteViews)
    // ne se combine pas bien avec les poids Glance.
    Box(GlanceModifier.width(width), contentAlignment = Alignment.CenterEnd) { ClockViews(c, timeSize) }
}

@Composable
private fun ClockViews(c: WidgetColors, timeSize: Float) {
    val context = LocalContext.current
    val views = RemoteViews(context.packageName, R.layout.widget_clock).apply {
        setTextColor(R.id.clock_date, c.secondaryColor.toArgb())
        setTextColor(R.id.clock_time, c.primaryColor.toArgb())
        setTextViewTextSize(R.id.clock_time, TypedValue.COMPLEX_UNIT_SP, timeSize)
    }
    AndroidRemoteViews(views)
}

/** Icône + température + lieu. */
@Composable
private fun CurrentBlock(data: LocalForecast, c: WidgetColors, big: Boolean) {
    val cur = data.forecast.current
    val condition = WeatherCondition.fromWmo(cur.weatherCode)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(condition.widgetIcon(), c.primary, if (big) 34.dp else 28.dp)
        Spacer(GlanceModifier.width(10.dp))
        Column {
            Text(
                "${cur.temperature.roundToInt()}°",
                style = TextStyle(color = c.primary, fontSize = if (big) 30.sp else 26.sp, fontWeight = FontWeight.Normal),
            )
            Text(data.place.name, style = TextStyle(color = c.secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        }
    }
}

/** En-tête commun : météo actuelle à gauche, date + heure à droite. */
@Composable
private fun Header(data: LocalForecast, c: WidgetColors) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CurrentBlock(data, c, big = true)
        Spacer(GlanceModifier.defaultWeight())
        Clock(c)
    }
}

/** Mini (2 × 1) : icône, température, lieu. */
@Composable
fun MiniContent(data: LocalForecast, c: WidgetColors) = CurrentBlock(data, c, big = false)

/** Pluie (4 × 1) : température, pluie dans les 2 h en barres, heure. */
@Composable
fun RainContent(data: LocalForecast, c: WidgetColors) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CurrentBlock(data, c, big = false)
        Spacer(GlanceModifier.width(14.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(rainText(data), style = TextStyle(color = c.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.height(6.dp))
            RainBars(data, c, maxHeight = 22)
        }
        Spacer(GlanceModifier.width(8.dp))
        Clock(c, timeSize = 22f, width = 72.dp)
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

/** Une colonne horaire : heure, icône, température, probabilité de pluie (texte épais, couleurs du texte). */
@Composable
private fun HourColumn(h: HourlyForecast, c: WidgetColors, modifier: GlanceModifier, compact: Boolean) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            h.time.substringAfter('T').take(2) + if (compact) "" else "h",
            style = TextStyle(color = c.secondary, fontSize = if (compact) 13.sp else 14.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(2.dp))
        Icon(WeatherCondition.fromWmo(h.weatherCode).widgetIcon(), c.primary, if (compact) 20.dp else 26.dp)
        Spacer(GlanceModifier.height(2.dp))
        Text("${h.temperature.roundToInt()}°", style = TextStyle(color = c.primary, fontSize = if (compact) 15.sp else 17.sp, fontWeight = FontWeight.Bold))
        Text(
            if (compact) "${h.precipitationProbability}" else "${h.precipitationProbability} %",
            style = TextStyle(color = c.secondary, fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.Bold),
        )
    }
}

/** Journée (4 × 2) : en-tête + 6 prochaines heures, compact. */
@Composable
fun DayContent(data: LocalForecast, c: WidgetColors) {
    Column(GlanceModifier.fillMaxWidth()) {
        Header(data, c)
        Spacer(GlanceModifier.height(6.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            data.forecast.hours.take(6).forEach { HourColumn(it, c, GlanceModifier.defaultWeight(), compact = false) }
        }
    }
}

/**
 * 12 heures (4 × 2) sur une ligne. Glance limite une ligne à 10 enfants : deux demi-lignes de 6.
 * Colonnes étroites : heure sans « h », probabilité sans « % » (légende à gauche du bloc).
 */
@Composable
fun TwelveHoursContent(data: LocalForecast, c: WidgetColors) {
    val hours = data.forecast.hours.take(12)
    Column(GlanceModifier.fillMaxWidth()) {
        Header(data, c)
        Spacer(GlanceModifier.height(6.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            hours.chunked(6).forEach { half ->
                Row(GlanceModifier.defaultWeight()) {
                    half.forEach { HourColumn(it, c, GlanceModifier.defaultWeight(), compact = true) }
                }
            }
        }
    }
}

/** Semaine (4 × 3) : en-tête + 5 jours. */
@Composable
fun WeekContent(data: LocalForecast, c: WidgetColors) {
    Column(GlanceModifier.fillMaxWidth()) {
        Header(data, c)
        Spacer(GlanceModifier.height(8.dp))
        data.forecast.days.take(5).forEach { d ->
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    LocalDate.parse(d.date).dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.FRENCH)
                        .replaceFirstChar { it.uppercase() },
                    style = TextStyle(color = c.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    modifier = GlanceModifier.width(46.dp),
                )
                Icon(WeatherCondition.fromWmo(d.weatherCode).widgetIcon(), c.primary, 18.dp)
                Spacer(GlanceModifier.defaultWeight())
                if (d.precipitationSum > 0) {
                    Text(
                        "%.1f mm".format(Locale.FRENCH, d.precipitationSum),
                        style = TextStyle(color = c.secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    )
                    Spacer(GlanceModifier.width(10.dp))
                }
                Text("${d.tempMin.roundToInt()}°", style = TextStyle(color = c.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.width(8.dp))
                Text("${d.tempMax.roundToInt()}°", style = TextStyle(color = c.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold))
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
            Text(label ?: "Pluie", style = TextStyle(color = c.secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        }
    }
}
