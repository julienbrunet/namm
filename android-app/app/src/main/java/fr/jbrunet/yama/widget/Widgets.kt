package fr.jbrunet.yama.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.layout.Alignment
import androidx.glance.action.Action
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import fr.jbrunet.yama.MainActivity
import fr.jbrunet.yama.MeteoApp
import fr.jbrunet.yama.data.local.replace
import fr.jbrunet.yama.ui.theme.DarkSlate
import fr.jbrunet.yama.ui.theme.LightSlate
import fr.jbrunet.yama.work.RefreshWorker
import java.io.File

enum class WidgetKind { MINI, RAIN, DAY, TWELVE, WEEK, MAP }

/**
 * Widget générique : lit la dernière prévision en cache (rafraîchie par [RefreshWorker])
 * et le thème choisi dans les paramètres, puis affiche la variante demandée.
 */
abstract class MeteoWidget(private val kind: WidgetKind) : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as MeteoApp
        val data = app.forecastRepository.cached()
        val settings = app.settings.current()
        val dark = settings.style.widgetDark(
            settings.theme,
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
        )
        // Réglages propres à CE widget (fond, couleur du texte).
        val style = WidgetConfigStore.read(context, GlanceAppWidgetManager(context).getAppWidgetId(id))
        val colors = WidgetColors(if (dark) DarkSlate else LightSlate, style)
        val map = if (kind == WidgetKind.MAP) WidgetMapStore.read(context) else null
        val open = openAction(context, kind)
        provideContent {
            val oneLine = kind == WidgetKind.MINI || kind == WidgetKind.RAIN
            WidgetFrame(
                colors, open,
                padding = if (kind == WidgetKind.MAP) 10.dp else 14.dp,
                alignment = if (oneLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                when {
                    kind == WidgetKind.MAP -> MapContent(map?.first, map?.second, colors)
                    data == null -> EmptyContent(colors)
                    kind == WidgetKind.MINI -> MiniContent(data, colors)
                    kind == WidgetKind.RAIN -> RainContent(data, colors)
                    // Journée / 12 h : nombre d'heures réglable par widget (6 ou 12).
                    kind == WidgetKind.DAY || kind == WidgetKind.TWELVE ->
                        if ((style.hours ?: if (kind == WidgetKind.TWELVE) 12 else 6) == 12) TwelveHoursContent(data, colors)
                        else DayContent(data, colors)
                    else -> WeekContent(data, colors)
                }
            }
        }
    }
}

class MiniWidget : MeteoWidget(WidgetKind.MINI)
class RainWidget : MeteoWidget(WidgetKind.RAIN)
class DayWidget : MeteoWidget(WidgetKind.DAY)
class WeekWidget : MeteoWidget(WidgetKind.WEEK)
class TwelveHoursWidget : MeteoWidget(WidgetKind.TWELVE)
class MapWidget : MeteoWidget(WidgetKind.MAP)

/** Supprime les réglages d'un widget retiré de l'écran d'accueil. */
abstract class MeteoWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetConfigStore.delete(context, appWidgetIds)
    }

    /** Réveil système (≈ 30 min, même en veille) : relance une actualisation si le travail périodique a pris du retard. */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        Widgets.refreshIfStale(context)
    }
}

class MiniWidgetReceiver : MeteoWidgetReceiver() { override val glanceAppWidget = MiniWidget() }
class RainWidgetReceiver : MeteoWidgetReceiver() { override val glanceAppWidget = RainWidget() }
class DayWidgetReceiver : MeteoWidgetReceiver() { override val glanceAppWidget = DayWidget() }
class WeekWidgetReceiver : MeteoWidgetReceiver() { override val glanceAppWidget = WeekWidget() }
class TwelveHoursWidgetReceiver : MeteoWidgetReceiver() { override val glanceAppWidget = TwelveHoursWidget() }

class MapWidgetReceiver : MeteoWidgetReceiver() {
    override val glanceAppWidget = MapWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Widgets.refreshNow(context) // calcule tout de suite la première image
    }
}

/** Le widget carte ouvre directement l'écran carte. */
private fun openAction(context: Context, kind: WidgetKind): Action =
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SCREEN, if (kind == WidgetKind.MAP) "map" else "forecast")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    )

/** Image du widget carte, recalculée par [RefreshWorker] (≈ toutes les 30 min). */
object WidgetMapStore {
    private fun file(context: Context) = File(context.filesDir, "widget_map.png")
    private fun prefs(context: Context) = context.getSharedPreferences("widget_map", Context.MODE_PRIVATE)

    fun read(context: Context) = file(context).takeIf { it.exists() }?.let { f ->
        BitmapFactory.decodeFile(f.path)?.let { it to prefs(context).getString("label", null) }
    }

    fun write(context: Context, bitmap: Bitmap, label: String) {
        val tmp = File(file(context).path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.replace(file(context))
        prefs(context).edit().putString("label", label).apply()
    }
}

object Widgets {
    /** Redessine tous les widgets (après une actualisation, un changement de thème…). */
    suspend fun updateAll(context: Context) {
        MiniWidget().updateAll(context)
        RainWidget().updateAll(context)
        DayWidget().updateAll(context)
        WeekWidget().updateAll(context)
        TwelveHoursWidget().updateAll(context)
        MapWidget().updateAll(context)
    }

    /**
     * Taille en pixels de l'image du widget carte, d'après la taille réelle du (premier) widget posé,
     * plafonnée à 720 px (limite mémoire des widgets).
     */
    suspend fun mapWidgetSizePx(context: Context): Pair<Int, Int> {
        val manager = GlanceAppWidgetManager(context)
        val id = manager.getGlanceIds(MapWidget::class.java).firstOrNull()
        val options = id?.let {
            AppWidgetManager.getInstance(context).getAppWidgetOptions(manager.getAppWidgetId(it))
        }
        val portrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        // En portrait : largeur mini et hauteur maxi ; marges et légende du widget déduites.
        val wDp = (options?.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) ?: 0).takeIf { it > 0 } ?: 320
        val hDp = (options?.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0).takeIf { it > 0 } ?: 240
        val w = (wDp - 20).coerceAtLeast(80).toDouble()
        val h = (hDp - 44).coerceAtLeast(80).toDouble()
        val scale = 720.0 / maxOf(w, h)
        return (w * scale).toInt() to (h * scale).toInt()
    }

    suspend fun hasMapWidget(context: Context) =
        GlanceAppWidgetManager(context).getGlanceIds(MapWidget::class.java).isNotEmpty()

    /** Actualisation complète si la dernière date de plus de 10 min (ouverture de l'appli, réveil des widgets). */
    fun refreshIfStale(context: Context) {
        val last = context.getSharedPreferences("worker", Context.MODE_PRIVATE).getLong("last_run", 0)
        if (System.currentTimeMillis() - last > 10 * 60_000L) refreshNow(context, replace = false)
    }

    /** Lance immédiatement une actualisation complète (prévision + image carte). */
    fun refreshNow(context: Context, replace: Boolean = true) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "refresh-now",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RefreshWorker>().build(),
        )
    }
}
