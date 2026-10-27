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
 * Generic widget: reads the last cached forecast (refreshed by [RefreshWorker])
 * and the theme chosen in the settings, then shows the requested variant.
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
        // Settings specific to THIS widget (background, text colour).
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
                    // Day / 12 h: number of hours set per widget (6 or 12).
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

/** Deletes the settings of a widget removed from the home screen. */
abstract class MeteoWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetConfigStore.delete(context, appWidgetIds)
    }

    /** System wake-up (≈ 30 min, even in doze): starts a refresh if the periodic work is late. */
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
        Widgets.refreshNow(context) // computes the first image straight away
    }
}

/** The map widget opens the map screen directly. */
private fun openAction(context: Context, kind: WidgetKind): Action =
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SCREEN, if (kind == WidgetKind.MAP) "map" else "forecast")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    )

/** Map widget image, recomputed by [RefreshWorker] (≈ every 30 min). */
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
    /** Redraws all widgets (after a refresh, a theme change…). */
    suspend fun updateAll(context: Context) {
        MiniWidget().updateAll(context)
        RainWidget().updateAll(context)
        DayWidget().updateAll(context)
        WeekWidget().updateAll(context)
        TwelveHoursWidget().updateAll(context)
        MapWidget().updateAll(context)
    }

    /**
     * Size in pixels of the map widget image, from the actual size of the (first) placed widget,
     * capped at 720 px (widget memory limit).
     */
    suspend fun mapWidgetSizePx(context: Context): Pair<Int, Int> {
        val manager = GlanceAppWidgetManager(context)
        val id = manager.getGlanceIds(MapWidget::class.java).firstOrNull()
        val options = id?.let {
            AppWidgetManager.getInstance(context).getAppWidgetOptions(manager.getAppWidgetId(it))
        }
        val portrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        // In portrait: minimum width and maximum height; widget margins and caption subtracted.
        val wDp = (options?.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) ?: 0).takeIf { it > 0 } ?: 320
        val hDp = (options?.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0).takeIf { it > 0 } ?: 240
        val w = (wDp - 20).coerceAtLeast(80).toDouble()
        val h = (hDp - 44).coerceAtLeast(80).toDouble()
        val scale = 720.0 / maxOf(w, h)
        return (w * scale).toInt() to (h * scale).toInt()
    }

    suspend fun hasMapWidget(context: Context) =
        GlanceAppWidgetManager(context).getGlanceIds(MapWidget::class.java).isNotEmpty()

    /** Full refresh if the last one is older than 10 min (app opened, widgets woken up). */
    fun refreshIfStale(context: Context) {
        val last = context.getSharedPreferences("worker", Context.MODE_PRIVATE).getLong("last_run", 0)
        if (System.currentTimeMillis() - last > 10 * 60_000L) refreshNow(context, replace = false)
    }

    /** Starts a full refresh right away (forecast + map image). */
    fun refreshNow(context: Context, replace: Boolean = true) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "refresh-now",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RefreshWorker>().build(),
        )
    }
}
