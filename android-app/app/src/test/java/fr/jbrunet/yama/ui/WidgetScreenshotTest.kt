package fr.jbrunet.yama.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import fr.jbrunet.yama.Fixtures
import fr.jbrunet.yama.R
import fr.jbrunet.yama.domain.MapFrame
import fr.jbrunet.yama.ui.theme.DarkSlate
import fr.jbrunet.yama.ui.theme.LightSlate
import fr.jbrunet.yama.widget.DayContent
import fr.jbrunet.yama.widget.MapContent
import fr.jbrunet.yama.widget.MiniContent
import fr.jbrunet.yama.widget.RainContent
import fr.jbrunet.yama.widget.WeekContent
import fr.jbrunet.yama.widget.TwelveHoursContent
import fr.jbrunet.yama.widget.WidgetBgColor
import fr.jbrunet.yama.widget.WidgetOpacity
import fr.jbrunet.yama.widget.WidgetStyle
import fr.jbrunet.yama.widget.WidgetTextColor
import fr.jbrunet.yama.widget.WidgetColors
import fr.jbrunet.yama.widget.WidgetFrame
import fr.jbrunet.yama.widget.WidgetMapRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real rendering of the Glance widgets (RemoteViews → View → PNG) in screenshots/widget_*.png (French in screenshots/fr/). */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
open class WidgetScreenshotTest {

    protected open val dir = "screenshots"

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val data = Fixtures.localForecast()
    private val dark = WidgetColors(DarkSlate)
    private val light = WidgetColors(LightSlate)

    private fun capture(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        val result = runBlocking {
            GlanceRemoteViews().compose(context, DpSize(width.dp, height.dp)) { content() }
        }
        // View attached to a real window (Robolectric activity): otherwise the TextClocks stay empty.
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val parent = FrameLayout(activity)
        val view = result.remoteViews.apply(activity, parent)
        val density = context.resources.displayMetrics.density
        val w = (width * density).toInt()
        val h = (height * density).toInt()
        parent.addView(view, ViewGroup.LayoutParams(w, h))
        activity.setContentView(parent, ViewGroup.LayoutParams(w, h))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // Fake wallpaper (sunset sky) to judge how readable transparent backgrounds are.
        val canvas = Canvas(bmp)
        canvas.drawPaint(android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFF9FB4CC.toInt(), 0xFFE9DCC8.toInt(), 0xFFF2C27A.toInt()), null,
                android.graphics.Shader.TileMode.CLAMP,
            )
        })
        parent.draw(canvas)
        File(dir).mkdirs()
        File("$dir/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val open = actionStartActivity(android.content.ComponentName("fr.jbrunet.yama", "fr.jbrunet.yama.MainActivity"))

    private fun style(bg: WidgetBgColor, opacity: WidgetOpacity, text: WidgetTextColor = WidgetTextColor.AUTO) =
        WidgetColors(DarkSlate, WidgetStyle(opacity, text, bg))

    @Test
    fun widgets() {
        capture("widget_mini", 170, 80) { WidgetFrame(dark, open) { MiniContent(data, dark) } }
        capture("widget_rain", 360, 80) { WidgetFrame(dark, open) { RainContent(data, dark) } }
        capture("widget_day", 360, 200) { WidgetFrame(dark, open) { DayContent(data, dark) } }
        val light70 = style(WidgetBgColor.LIGHT, WidgetOpacity.HIGH)
        capture("widget_day_light_70", 360, 170) { WidgetFrame(light70, open) { DayContent(data, light70) } }
        val transparentDark = style(WidgetBgColor.LIGHT, WidgetOpacity.TRANSPARENT, WidgetTextColor.DARK)
        capture("widget_day_transparent", 360, 170) { WidgetFrame(transparentDark, open) { DayContent(data, transparentDark) } }
        capture("widget_12h", 360, 170) { WidgetFrame(dark, open) { TwelveHoursContent(data, dark) } }
        val dark40 = style(WidgetBgColor.DARK, WidgetOpacity.LOW, WidgetTextColor.LIGHT)
        capture("widget_12h_dark_40", 360, 170) { WidgetFrame(dark40, open) { TwelveHoursContent(data, dark40) } }
        capture("widget_week", 360, 250) { WidgetFrame(light, open) { WeekContent(data, light) } }
        val map = Fixtures.rainMap()
        val frame = map.frames.filterIsInstance<MapFrame.Forecast>()[3]
        val z = WidgetMapRenderer.zoomFor(Fixtures.paris.latitude, 720)
        val image = WidgetMapRenderer.render(500, 720, Fixtures.paris.latitude, Fixtures.paris.longitude, z, frame, true, 0xFF2F333D.toInt()) { _, _, _ -> null }
        capture("widget_map", 300, 460) {
            WidgetFrame(dark, open, padding = 10.dp) { MapContent(image, context.getString(R.string.widget_map_caption, "Paris", "20:45", "AROME-PI", "20:30"), dark) }
        }
    }
}

/** Same widgets in French. */
@Config(qualifiers = "+fr")
class FrenchWidgetScreenshotTest : WidgetScreenshotTest() {
    override val dir = "screenshots/fr"
}
