package fr.jbrunet.yama.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import fr.jbrunet.yama.R

/** Opacité du fond d'un widget. */
enum class WidgetOpacity(val alpha: Float) {
    OPAQUE(1f),
    HIGH(0.7f),
    LOW(0.4f),
    TRANSPARENT(0f),
}

/** Couleur du fond d'un widget (Auto = thème de l'app). */
enum class WidgetBgColor { AUTO, LIGHT, DARK }

/** Couleur du texte et des icônes d'un widget (Auto = contraste avec le fond choisi). */
enum class WidgetTextColor { AUTO, LIGHT, DARK }

/** Libellé d'une option dans les réglages du widget. */
fun WidgetOpacity.label(context: Context): String = when (this) {
    WidgetOpacity.OPAQUE -> context.getString(R.string.option_opaque)
    WidgetOpacity.TRANSPARENT -> context.getString(R.string.option_transparent)
    else -> "${(alpha * 100).toInt()} %"
}

fun WidgetBgColor.label(context: Context): String = context.getString(
    when (this) {
        WidgetBgColor.AUTO -> R.string.option_auto
        WidgetBgColor.LIGHT -> R.string.option_light
        WidgetBgColor.DARK -> R.string.option_dark
    },
)

fun WidgetTextColor.label(context: Context): String = context.getString(
    when (this) {
        WidgetTextColor.AUTO -> R.string.option_auto
        WidgetTextColor.LIGHT -> R.string.option_white
        WidgetTextColor.DARK -> R.string.option_dark_text
    },
)

data class WidgetStyle(
    val opacity: WidgetOpacity = WidgetOpacity.OPAQUE,
    val text: WidgetTextColor = WidgetTextColor.AUTO,
    val background: WidgetBgColor = WidgetBgColor.AUTO,
    /** Heures de prévision affichées (widgets Journée / 12 h) ; null = défaut du widget. */
    val hours: Int? = null,
)

/** Réglages propres à chaque widget posé (clé = appWidgetId Android). */
object WidgetConfigStore {
    private fun prefs(context: Context) = context.getSharedPreferences("widget_config", Context.MODE_PRIVATE)

    fun read(context: Context, appWidgetId: Int): WidgetStyle {
        val p = prefs(context)
        return WidgetStyle(
            opacity = p.getString("opacity_$appWidgetId", null)?.let { runCatching { WidgetOpacity.valueOf(it) }.getOrNull() }
                ?: WidgetOpacity.OPAQUE,
            text = p.getString("text_$appWidgetId", null)?.let { runCatching { WidgetTextColor.valueOf(it) }.getOrNull() }
                ?: WidgetTextColor.AUTO,
            background = p.getString("bg_$appWidgetId", null)?.let { runCatching { WidgetBgColor.valueOf(it) }.getOrNull() }
                ?: WidgetBgColor.AUTO,
            hours = p.getInt("hours_$appWidgetId", 0).takeIf { it > 0 },
        )
    }

    fun write(context: Context, appWidgetId: Int, style: WidgetStyle) {
        prefs(context).edit()
            .putString("opacity_$appWidgetId", style.opacity.name)
            .putString("text_$appWidgetId", style.text.name)
            .putString("bg_$appWidgetId", style.background.name)
            .apply { style.hours?.let { putInt("hours_$appWidgetId", it) } }
            .apply()
    }

    fun delete(context: Context, appWidgetIds: IntArray) {
        val e = prefs(context).edit()
        appWidgetIds.forEach { e.remove("opacity_$it").remove("text_$it").remove("bg_$it").remove("hours_$it") }
        e.apply()
    }
}

/** Texte clair ou foncé imposé (sinon : couleurs du thème). */
internal object WidgetTextPalettes {
    val light = Triple(Color(0xFFFFFFFF), Color(0xE6FFFFFF), Color(0xB3FFFFFF))
    val dark = Triple(Color(0xFF2B2F38), Color(0xFF4A505C), Color(0xFF6E7480))
    val lightRain = Color(0xFFA9C8EC)
    val darkRain = Color(0xFF2F6DB5)
}
