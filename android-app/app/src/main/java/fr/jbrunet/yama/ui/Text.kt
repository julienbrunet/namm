package fr.jbrunet.yama.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import fr.jbrunet.yama.R
import fr.jbrunet.yama.data.repository.LoadStage
import fr.jbrunet.yama.data.repository.StaleReason
import fr.jbrunet.yama.domain.ForecastSource
import fr.jbrunet.yama.domain.WeatherCondition
import fr.jbrunet.yama.ui.map.RainLevel
import fr.jbrunet.yama.ui.theme.ThemeMode
import fr.jbrunet.yama.ui.theme.ThemeStyle
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/**
 * Text built outside the UI (view models, workers) and resolved in the user's language when shown.
 * Arguments that are themselves [UiText] are resolved too.
 */
data class UiText(@StringRes val id: Int, val args: List<Any> = emptyList()) {
    constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())

    fun resolve(context: Context): String =
        context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
}

@Composable
fun UiText.resolve(): String {
    LocalConfiguration.current // re-read when the language changes
    return resolve(LocalContext.current)
}

/** Language used for dates and numbers. */
@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** Formats [time] with a localised pattern from the resources (e.g. [R.string.date_long_pattern]). */
fun Context.formatDate(@StringRes pattern: Int, time: TemporalAccessor, locale: Locale = resources.configuration.locales[0]): String =
    DateTimeFormatter.ofPattern(getString(pattern), locale).format(time).replaceFirstChar { it.titlecase(locale) }

@get:StringRes
val WeatherCondition.labelRes: Int
    get() = when (this) {
        WeatherCondition.CLEAR -> R.string.condition_clear
        WeatherCondition.PARTLY_CLOUDY -> R.string.condition_partly_cloudy
        WeatherCondition.CLOUDY -> R.string.condition_cloudy
        WeatherCondition.FOG -> R.string.condition_fog
        WeatherCondition.DRIZZLE -> R.string.condition_drizzle
        WeatherCondition.RAIN -> R.string.condition_rain
        WeatherCondition.SNOW -> R.string.condition_snow
        WeatherCondition.STORM -> R.string.condition_storm
    }

@get:StringRes
val StaleReason.labelRes: Int
    get() = when (this) {
        StaleReason.NO_NETWORK -> R.string.stale_no_network
        StaleReason.RATE_LIMITED -> R.string.stale_rate_limited
        StaleReason.ERROR -> R.string.stale_error
    }

@get:StringRes
val ForecastSource.labelRes: Int
    get() = when (this) {
        ForecastSource.OPEN_METEO -> R.string.source_open_meteo
        ForecastSource.AROME_PI -> R.string.source_arome_pi
    }

@get:StringRes
val LoadStage.labelRes: Int
    get() = when (this) {
        LoadStage.METEO_FRANCE -> R.string.stage_meteo_france
        LoadStage.OPEN_METEO -> R.string.stage_open_meteo
        LoadStage.PREPARING -> R.string.stage_preparing
    }

/** Legend label under the key rain levels, null for the others. */
@get:StringRes
val RainLevel.labelRes: Int?
    get() = when (this) {
        RainLevel.VERY_LIGHT -> R.string.rain_level_light
        RainLevel.MODERATE -> R.string.rain_level_moderate
        RainLevel.HEAVY -> R.string.rain_level_heavy
        RainLevel.EXTREME -> R.string.rain_level_intense
        RainLevel.LIGHT, RainLevel.VERY_HEAVY -> null
    }

@get:StringRes
val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_mode_system
        ThemeMode.DARK -> R.string.theme_mode_dark
        ThemeMode.LIGHT -> R.string.theme_mode_light
    }

@get:StringRes
val ThemeStyle.labelRes: Int
    get() = when (this) {
        ThemeStyle.SLATE -> R.string.theme_style_slate
        ThemeStyle.SKY -> R.string.theme_style_sky
        ThemeStyle.PASTEL -> R.string.theme_style_pastel
        ThemeStyle.AURORA -> R.string.theme_style_aurora
        ThemeStyle.NATURE -> R.string.theme_style_nature
    }
