package fr.jbrunet.yama.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Line-drawn weather icons (“outlined” style) missing from Material Icons. */
object WeatherIcons {

    private const val CLOUD = "M7,14.5a4,4 0,0 1,-0.6 -7.95A5.5,5.5 0,0 1,17.2 6.9a3.8,3.8 0,0 1,0.3 7.6H7z"

    /** Cloud + three drops (rain). */
    val Rain: ImageVector by lazy { cloudWith("M8.5,17.5l-1,2.5", "M12.5,17.5l-1,2.5", "M16.5,17.5l-1,2.5") }

    /** Cloud + two small drops (drizzle). */
    val Drizzle: ImageVector by lazy { cloudWith("M9.5,18v1.5", "M14.5,18v1.5") }

    private fun cloudWith(vararg drops: String) = ImageVector.Builder(
        name = "WeatherCloud",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        (listOf(CLOUD) + drops).forEach { path ->
            addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black), // tint replaced by Icon(tint = …)
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
}
