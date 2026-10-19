package com.namm.meteo.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.namm.meteo.MeteoApp
import com.namm.meteo.R
import com.namm.meteo.ui.components.SectionLabel
import com.namm.meteo.ui.components.TileShape
import com.namm.meteo.ui.theme.LocalSlate
import com.namm.meteo.ui.theme.MeteoTheme
import com.namm.meteo.ui.theme.Slate
import com.namm.meteo.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/**
 * Réglages d'UN widget (fond, couleur du texte). Ouvert par Android à la pose du widget
 * (facultatif) et via appui long → « Paramètres » (widget reconfigurable).
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // Si l'utilisateur quitte sans valider : réglages par défaut (widget conservé).
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        val app = application as MeteoApp
        val initial = WidgetConfigStore.read(this, appWidgetId)
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className.orEmpty()
        val isMap = provider.endsWith("MapWidgetReceiver")
        // Journée et 12 h : choix du nombre d'heures (défaut 6 ou 12 selon le widget).
        val defaultHours = when {
            provider.endsWith("TwelveHoursWidgetReceiver") -> 12
            provider.endsWith("DayWidgetReceiver") -> 6
            else -> null
        }

        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val scope = rememberCoroutineScope()
            MeteoTheme(settings?.theme ?: ThemeMode.SYSTEM) {
                WidgetConfigContent(
                    initial = initial.copy(hours = initial.hours ?: defaultHours),
                    isMap = isMap,
                    onSave = { style ->
                        WidgetConfigStore.write(this, appWidgetId, style)
                        scope.launch {
                            Widgets.updateAll(applicationContext)
                            finish()
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun WidgetConfigContent(initial: WidgetStyle, isMap: Boolean, onSave: (WidgetStyle) -> Unit) {
    var opacity by remember { mutableStateOf(initial.opacity) }
    var text by remember { mutableStateOf(initial.text) }
    var bg by remember { mutableStateOf(initial.background) }
    var hours by remember { mutableStateOf(initial.hours) }
    Column(
        Modifier.fillMaxSize().background(Slate.Background).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Text("Réglages du widget", style = MaterialTheme.typography.headlineLarge, color = Slate.Primary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Propres à ce widget uniquement. Modifiables plus tard par un appui long sur le widget.",
            color = Slate.Secondary,
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel("APERÇU")
        Preview(WidgetStyle(opacity, text, bg), isMap)

        Spacer(Modifier.height(24.dp))
        SectionLabel("COULEUR DU FOND")
        Choice(WidgetBgColor.entries, bg, { it.label }) { bg = it }

        Spacer(Modifier.height(20.dp))
        SectionLabel("OPACITÉ DU FOND")
        Choice(WidgetOpacity.entries, opacity, { it.label }) { opacity = it }

        Spacer(Modifier.height(20.dp))
        SectionLabel("TEXTE ET ICÔNES")
        Choice(WidgetTextColor.entries, text, { it.label }) { text = it }

        // Uniquement pour les widgets Journée / 12 h.
        hours?.let { h ->
            Spacer(Modifier.height(20.dp))
            SectionLabel("HEURES AFFICHÉES")
            Choice(listOf(6, 12), h, { "$it heures" }) { hours = it }
        }

        Spacer(Modifier.height(32.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Slate.Primary)
                .clickable { onSave(WidgetStyle(opacity, text, bg, hours)) }.padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Valider", color = Slate.Background, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Aperçu du widget sur un faux fond d'écran (dégradé), avec les mêmes couleurs que le vrai. */
@Composable
private fun Preview(style: WidgetStyle, isMap: Boolean) {
    val palette = LocalSlate.current
    val colors = WidgetColors(palette, style)
    val text = Triple(colors.primaryColor, colors.secondaryColor, colors.secondaryColor)
    Box(
        Modifier.fillMaxWidth().height(150.dp).clip(TileShape)
            .background(Brush.linearGradient(listOf(Color(0xFFE7B47C), Color(0xFF6D8FB5), Color(0xFF2E4A3B)))),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(16.dp).clip(RoundedCornerShape(22.dp)).background(colors.background)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(if (isMap) R.drawable.ic_w_drop else R.drawable.ic_w_rain), null,
                tint = text.first, modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(if (isMap) "Carte de pluie" else "15°", color = text.first, fontSize = if (isMap) 18.sp else 30.sp)
                Text("Clermont-Ferrand", color = text.second, fontSize = 13.sp)
                Text("Pluie dans 20 min", color = colors.rainColor, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .background(if (isSelected) Slate.TileHighlight else Slate.Tile)
                    .clickable { onSelect(option) }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    color = if (isSelected) Slate.Primary else Slate.Secondary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
        }
    }
}
