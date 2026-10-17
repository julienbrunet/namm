package com.namm.meteo.ui

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
import com.namm.meteo.Fixtures
import com.namm.meteo.domain.MapFrame
import com.namm.meteo.ui.theme.DarkSlate
import com.namm.meteo.ui.theme.LightSlate
import com.namm.meteo.widget.DayContent
import com.namm.meteo.widget.MapContent
import com.namm.meteo.widget.MiniContent
import com.namm.meteo.widget.RainContent
import com.namm.meteo.widget.WeekContent
import com.namm.meteo.widget.WidgetColors
import com.namm.meteo.widget.WidgetFrame
import com.namm.meteo.widget.WidgetMapRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Rendu réel des widgets Glance (RemoteViews → View → PNG) dans screenshots/widget_*.png. */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class WidgetScreenshotTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val data = Fixtures.localForecast()
    private val dark = WidgetColors(DarkSlate)
    private val light = WidgetColors(LightSlate)

    private fun capture(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        val result = runBlocking {
            GlanceRemoteViews().compose(context, DpSize(width.dp, height.dp)) { content() }
        }
        val parent = FrameLayout(context)
        val view = result.remoteViews.apply(context, parent)
        val density = context.resources.displayMetrics.density
        val w = (width * density).toInt()
        val h = (height * density).toInt()
        parent.addView(view, ViewGroup.LayoutParams(w, h))
        parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF7A8090.toInt()) // fond d'écran d'accueil fictif
        parent.draw(Canvas(bmp))
        File("screenshots").mkdirs()
        File("screenshots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val open = actionStartActivity(android.content.ComponentName("com.namm.meteo", "com.namm.meteo.MainActivity"))

    @Test
    fun widgets() {
        capture("widget_mini", 160, 80) { WidgetFrame(dark, open) { MiniContent(data, dark) } }
        capture("widget_pluie", 340, 80) { WidgetFrame(dark, open) { RainContent(data, dark) } }
        capture("widget_journee", 340, 170) { WidgetFrame(dark, open) { DayContent(data, dark) } }
        capture("widget_semaine", 340, 250) { WidgetFrame(light, open) { WeekContent(data, light) } }
        val map = Fixtures.rainMap()
        val frame = map.frames.filterIsInstance<MapFrame.Forecast>()[3]
        val image = WidgetMapRenderer.render(600, 480, Fixtures.paris.latitude, Fixtures.paris.longitude, frame, true, 0xFF2F333D.toInt()) { _, _, _ -> null }
        capture("widget_carte", 340, 250) {
            WidgetFrame(dark, open, padding = 10.dp) { MapContent(image, "Paris · pluie à 20:45 · AROME-PI", dark) }
        }
    }
}
