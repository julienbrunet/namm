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
import com.namm.meteo.widget.TwelveHoursContent
import com.namm.meteo.widget.WidgetBgColor
import com.namm.meteo.widget.WidgetOpacity
import com.namm.meteo.widget.WidgetStyle
import com.namm.meteo.widget.WidgetTextColor
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
        // Vue attachée à une vraie fenêtre (activité Robolectric) : sinon les TextClock restent vides.
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
        // Faux fond d'écran (ciel au couchant) pour juger la lisibilité des fonds transparents.
        val canvas = Canvas(bmp)
        canvas.drawPaint(android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFF9FB4CC.toInt(), 0xFFE9DCC8.toInt(), 0xFFF2C27A.toInt()), null,
                android.graphics.Shader.TileMode.CLAMP,
            )
        })
        parent.draw(canvas)
        File("screenshots").mkdirs()
        File("screenshots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val open = actionStartActivity(android.content.ComponentName("com.namm.meteo", "com.namm.meteo.MainActivity"))

    private fun style(bg: WidgetBgColor, opacity: WidgetOpacity, text: WidgetTextColor = WidgetTextColor.AUTO) =
        WidgetColors(DarkSlate, WidgetStyle(opacity, text, bg))

    @Test
    fun widgets() {
        capture("widget_mini", 170, 80) { WidgetFrame(dark, open) { MiniContent(data, dark) } }
        capture("widget_pluie", 360, 80) { WidgetFrame(dark, open) { RainContent(data, dark) } }
        capture("widget_journee", 360, 200) { WidgetFrame(dark, open) { DayContent(data, dark) } }
        val clair70 = style(WidgetBgColor.LIGHT, WidgetOpacity.HIGH)
        capture("widget_journee_clair_70", 360, 170) { WidgetFrame(clair70, open) { DayContent(data, clair70) } }
        val transparentFonce = style(WidgetBgColor.LIGHT, WidgetOpacity.TRANSPARENT, WidgetTextColor.DARK)
        capture("widget_journee_transparent", 360, 170) { WidgetFrame(transparentFonce, open) { DayContent(data, transparentFonce) } }
        capture("widget_12h", 360, 170) { WidgetFrame(dark, open) { TwelveHoursContent(data, dark) } }
        val sombre40 = style(WidgetBgColor.DARK, WidgetOpacity.LOW, WidgetTextColor.LIGHT)
        capture("widget_12h_sombre_40", 360, 170) { WidgetFrame(sombre40, open) { TwelveHoursContent(data, sombre40) } }
        capture("widget_semaine", 360, 250) { WidgetFrame(light, open) { WeekContent(data, light) } }
        val map = Fixtures.rainMap()
        val frame = map.frames.filterIsInstance<MapFrame.Forecast>()[3]
        val z = WidgetMapRenderer.zoomFor(Fixtures.paris.latitude, 720)
        val image = WidgetMapRenderer.render(500, 720, Fixtures.paris.latitude, Fixtures.paris.longitude, z, frame, true, 0xFF2F333D.toInt()) { _, _, _ -> null }
        capture("widget_carte", 300, 460) {
            WidgetFrame(dark, open, padding = 10.dp) { MapContent(image, "Paris · pluie à 20:45 · AROME-PI", dark) }
        }
    }
}
