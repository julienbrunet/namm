package fr.jbrunet.yama.ui.forecast

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.jbrunet.yama.domain.HourlyForecast
import fr.jbrunet.yama.domain.WeatherCondition
import fr.jbrunet.yama.ui.components.TileShape
import fr.jbrunet.yama.ui.components.icon
import fr.jbrunet.yama.ui.labelRes
import fr.jbrunet.yama.ui.theme.Slate
import kotlin.math.roundToInt

private val ColumnWidth = 52.dp
private val CurveHeight = 34.dp

/** Prochaines 24 h : heure, icône, température, courbe et probabilité de pluie (défilement horizontal). */
@Composable
fun HourlyTile(hours: List<HourlyForecast>) {
    if (hours.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).padding(vertical = 14.dp)) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            HourRow(hours) { Text(it.time.substringAfter('T'), color = Slate.Secondary, style = MaterialTheme.typography.labelSmall) }
            Spacer(Modifier.height(8.dp))
            HourRow(hours) {
                val condition = WeatherCondition.fromWmo(it.weatherCode)
                Icon(condition.icon(), stringResource(condition.labelRes), tint = Slate.Primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(6.dp))
            HourRow(hours) {
                Text("${it.temperature.roundToInt()}°", color = Slate.Primary, style = MaterialTheme.typography.titleMedium)
            }
            TemperatureCurve(hours, ColumnWidth * hours.size)
            HourRow(hours) {
                val p = it.precipitationProbability
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.WaterDrop, null,
                        tint = if (p >= 30) Slate.Rain else Slate.Muted,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "$p %",
                        color = if (p >= 30) Slate.Rain else Slate.Muted,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun HourRow(hours: List<HourlyForecast>, cell: @Composable (HourlyForecast) -> Unit) {
    Row {
        hours.forEach {
            Column(Modifier.width(ColumnWidth), horizontalAlignment = Alignment.CenterHorizontally) { cell(it) }
        }
    }
}

/** Courbe lissée des températures, un point au centre de chaque colonne. */
@Composable
private fun TemperatureCurve(hours: List<HourlyForecast>, width: Dp) {
    val primary = Slate.Primary
    val curve = Slate.CurveColors
    Canvas(Modifier.width(width).height(CurveHeight)) {
        val temps = hours.map { it.temperature }
        val min = temps.min()
        val range = (temps.max() - min).coerceAtLeast(1.0)
        val pad = 6.dp.toPx()
        val step = size.width / hours.size
        val points = temps.mapIndexed { i, t ->
            Offset(step * (i + 0.5f), pad + ((1 - (t - min) / range) * (size.height - 2 * pad)).toFloat())
        }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.zipWithNext().forEach { (a, b) ->
                val mid = (a.x + b.x) / 2
                cubicTo(mid, a.y, mid, b.y, b.x, b.y)
            }
        }
        drawPath(
            path,
            brush = Brush.horizontalGradient(curve),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
        points.forEach { drawCircle(primary, radius = 3.dp.toPx(), center = it) }
    }
}
