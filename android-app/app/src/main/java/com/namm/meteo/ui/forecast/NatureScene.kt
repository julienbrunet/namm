package com.namm.meteo.ui.forecast

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.namm.meteo.domain.DayPhase
import com.namm.meteo.domain.SunPosition
import com.namm.meteo.domain.WeatherCondition
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Paysage zen dessiné (aucune image embarquée) : ciel selon l'heure, soleil ou lune, nuages,
 * montagnes en plans successifs, pins, lac avec reflets, puis pluie, neige, brume ou orage.
 * Tout est calculé une fois par taille d'écran et par météo ([drawWithCache]).
 */
@Composable
fun NatureScene(condition: WeatherCondition, sun: SunPosition, modifier: Modifier = Modifier) {
    Spacer(modifier.drawWithCache { buildScene(condition, sun) })
}

/** Ligne d'horizon (rive du lac), en fraction de la hauteur. */
private const val HORIZON = 0.62f

/** Ciel de haut en bas : zénith, milieu, horizon. */
private fun skyColors(phase: DayPhase): List<Color> = when (phase) {
    DayPhase.DAY -> listOf(Color(0xFF3B6CA0), Color(0xFF6B9AC4), Color(0xFFC3D8E6))
    DayPhase.DAWN -> listOf(Color(0xFF2E3D6B), Color(0xFFA9839F), Color(0xFFF3C6A0))
    DayPhase.DUSK -> listOf(Color(0xFF29285A), Color(0xFF9A5C7E), Color(0xFFF09C6E))
    DayPhase.NIGHT -> listOf(Color(0xFF0A0F25), Color(0xFF162047), Color(0xFF2D3A66))
}

/** Part de ciel voilé (0 = dégagé) et assombrissement propres à la météo. */
private val WeatherCondition.overcast: Float
    get() = when (this) {
        WeatherCondition.CLEAR -> 0f
        WeatherCondition.PARTLY_CLOUDY -> 0.15f
        WeatherCondition.CLOUDY, WeatherCondition.SNOW -> 0.55f
        WeatherCondition.FOG -> 0.65f
        WeatherCondition.DRIZZLE -> 0.5f
        WeatherCondition.RAIN -> 0.62f
        WeatherCondition.STORM -> 0.75f
    }

private val WeatherCondition.gloom: Float
    get() = when (this) {
        WeatherCondition.RAIN -> 0.18f
        WeatherCondition.STORM -> 0.38f
        WeatherCondition.CLOUDY, WeatherCondition.DRIZZLE -> 0.08f
        else -> 0f
    }

/** Couleur ternie vers son gris (ciel couvert) puis assombrie. */
private fun Color.muted(amount: Float, darken: Float): Color {
    val l = 0.299f * red + 0.587f * green + 0.114f * blue
    val c = lerp(this, Color(l, l, (l * 1.04f).coerceAtMost(1f)), amount) // gris à peine bleuté
    return Color(c.red * (1 - darken), c.green * (1 - darken), c.blue * (1 - darken), c.alpha)
}

private fun CacheDrawScope.buildScene(condition: WeatherCondition, sun: SunPosition) = run {
    val w = size.width
    val h = size.height
    val horizon = h * HORIZON
    val overcast = condition.overcast
    val gloom = condition.gloom
    val phase = sun.phase
    val night = phase == DayPhase.NIGHT
    val sky = skyColors(phase).map { it.muted(overcast, gloom) }
    val rnd = Random(7)

    // Soleil (à droite, sur un arc) ou lune ; voilé par les nuages.
    val sunX = w * (0.58f + 0.32f * sun.progress.coerceIn(0f, 1f))
    // Au lever et au coucher, l'astre se pose sur les crêtes les plus lointaines.
    val sunY = horizon - h * (0.16f + 0.18f * sin(PI * sun.progress.coerceIn(0f, 1f)).toFloat())
    val sunColor = when (phase) {
        DayPhase.DAY -> Color(0xFFFFF4DA)
        DayPhase.DAWN -> Color(0xFFFFE0B8)
        DayPhase.DUSK -> Color(0xFFFFC890)
        DayPhase.NIGHT -> Color(0xFFF1F0E6)
    }
    val lightVisible = (1f - overcast * 1.3f).coerceIn(0f, 1f)
    val moonCenter = Offset(w * 0.78f, h * 0.15f)
    val moonRadius = w * 0.045f
    val moon = Path().apply {
        addOval(Rect(moonCenter, moonRadius))
        val bite = Path().apply { addOval(Rect(moonCenter + Offset(moonRadius * 0.55f, -moonRadius * 0.25f), moonRadius * 0.9f)) }
        op(this, bite, PathOperation.Difference)
    }
    val stars = List(70) {
        Triple(Offset(rnd.nextFloat() * w, rnd.nextFloat() * horizon * 0.75f), rnd.nextFloat() * 1.6f + 0.6f, rnd.nextFloat() * 0.6f + 0.3f)
    }
    val lightCenter = if (night) moonCenter else Offset(sunX, sunY)

    // Montagnes : trois plans de plus en plus sombres, comme dans la brume.
    val farColor = lerp(sky[1], sky[2], 0.55f).muted(0.25f, if (night) 0f else 0.18f).let {
        if (condition == WeatherCondition.SNOW) lerp(it, Color(0xFFE3E8EE), 0.45f) else it
    }
    val midColor = lerp(farColor, sky[0], 0.35f).muted(0.1f, 0.32f)
    val nearColor = lerp(midColor, Color(0xFF0B1418), 0.55f)
    val far = ridge(w, horizon, h * 0.20f, h * 0.11f, seed = 3, peaks = 4)
    val mid = ridge(w, horizon, h * 0.12f, h * 0.06f, seed = 11, peaks = 6)
    val near = ridge(w, horizon + h * 0.012f, h * 0.045f, h * 0.02f, seed = 23, peaks = 3)
    val pines = Path().apply {
        listOf(0.04f, 0.09f, 0.13f, 0.2f, 0.71f, 0.77f, 0.83f, 0.9f, 0.95f).forEachIndexed { i, x ->
            val height = h * (0.05f + 0.035f * ((i * 37) % 10) / 10f)
            addPine(this, w * x, horizon + h * 0.004f, height)
        }
    }

    // Lac : reflet du ciel, puis rive sombre ; reflets du soleil / de la lune en traits.
    val lakeTop = lerp(sky[2], sky[1], 0.3f).muted(0.15f, 0.25f)
    val lakeBottom = lerp(sky[0], Color.Black, 0.45f)
    val reflection = Path().apply { addPath(mid); transform(mirror(horizon)) }
    val shimmer = List(14) { i ->
        val y = horizon + h * 0.012f + i * i * h * 0.0006f
        val half = w * (0.06f - i * 0.0028f) * (0.7f + rnd.nextFloat() * 0.6f)
        Pair(Offset(lightCenter.x - half, y), Offset(lightCenter.x + half, y))
    }
    val ripples = List(14) { Offset(rnd.nextFloat() * w, horizon + h * 0.025f + rnd.nextFloat() * h * 0.1f) }

    // Nuages : plus nombreux, plus bas et plus sombres quand le temps se gâte.
    val cloudCount = when (condition) {
        WeatherCondition.CLEAR -> 1
        WeatherCondition.PARTLY_CLOUDY -> 3
        WeatherCondition.FOG -> 2
        WeatherCondition.CLOUDY, WeatherCondition.DRIZZLE, WeatherCondition.SNOW -> 6
        WeatherCondition.RAIN -> 7
        WeatherCondition.STORM -> 8
    }
    val cloudBase = when (phase) {
        DayPhase.DAY -> Color(0xFFF7F9FC)
        DayPhase.DAWN -> Color(0xFFF6D9D6)
        DayPhase.DUSK -> Color(0xFFE8B7B3)
        DayPhase.NIGHT -> Color(0xFF8C97B8)
    }.muted(overcast * 0.8f, gloom * 1.6f + if (night) 0.35f else 0f)
    val clouds = List(cloudCount) { i ->
        val width = w * (0.32f + rnd.nextFloat() * 0.28f) * (if (overcast > 0.5f) 1.25f else 1f)
        // Ciel dégagé : un seul petit nuage, loin du soleil.
        val x = if (cloudCount == 1) w * 0.2f else (i + 0.5f) / cloudCount * w + (rnd.nextFloat() - 0.5f) * w * 0.18f
        val y = h * (0.05f + rnd.nextFloat() * 0.3f) + (if (overcast > 0.5f) -h * 0.02f else 0f)
        cloud(x, y, width)
    }
    val cloudAlpha = if (night) 0.55f else 0.85f

    val drops = when (condition) {
        WeatherCondition.RAIN, WeatherCondition.STORM -> 170
        WeatherCondition.DRIZZLE -> 80
        else -> 0
    }
    // Pluie jusqu'au lac seulement (des ronds dans l'eau ensuite) : rien ne raye les tuiles.
    val rain = List(drops) { Offset(rnd.nextFloat() * w * 1.1f, rnd.nextFloat() * (horizon + h * 0.04f)) to (0.2f + rnd.nextFloat() * 0.3f) }
    val dropLength = h * if (condition == WeatherCondition.DRIZZLE) 0.012f else 0.026f
    val flakes = if (condition == WeatherCondition.SNOW) {
        List(140) { Triple(Offset(rnd.nextFloat() * w, rnd.nextFloat() * h), 1f + rnd.nextFloat() * 2.6f, 0.45f + rnd.nextFloat() * 0.5f) }
    } else emptyList()
    val bolt = Path().apply {
        // À droite, loin du texte.
        val x = w * 0.8f
        val y = h * 0.2f
        moveTo(x, y)
        listOf(-0.03f to 0.07f, 0.02f to 0.075f, -0.04f to 0.16f, 0.0f to 0.17f, -0.03f to 0.27f).forEach { (dx, dy) ->
            lineTo(x + w * dx, y + h * dy * 0.6f)
        }
    }
    val fogBands = when {
        condition == WeatherCondition.FOG -> listOf(0.42f to 0.34f, 0.5f to 0.4f, 0.58f to 0.45f, 0.66f to 0.35f)
        // Brume matinale sur le lac.
        phase == DayPhase.DAWN || overcast >= 0.5f -> listOf(horizon / h - 0.01f to 0.14f)
        else -> emptyList()
    }
    val dp = density

    onDrawBehind {
        drawRect(Brush.verticalGradient(0f to sky[0], 0.42f to sky[1], HORIZON to sky[2], endY = size.height))

        if (night) {
            stars.forEach { (p, r, a) -> drawCircle(Color.White.copy(alpha = a * lightVisible), r * dp, p) }
        }
        // Halo puis astre.
        val glowAlpha = (if (phase == DayPhase.DAY) 0.38f else 0.55f) * (1f - overcast * 0.8f)
        drawCircle(
            Brush.radialGradient(listOf(sunColor.copy(alpha = glowAlpha), Color.Transparent), lightCenter, w * 0.42f),
            w * 0.42f,
            lightCenter,
        )
        if (night) drawPath(moon, sunColor.copy(alpha = 0.92f * lightVisible))
        else drawCircle(sunColor.copy(alpha = lightVisible), w * 0.055f, lightCenter)

        clouds.forEach { (path, bounds) ->
            drawPath(
                path,
                Brush.verticalGradient(
                    listOf(cloudBase.copy(alpha = cloudAlpha), cloudBase.muted(0.2f, 0.18f).copy(alpha = cloudAlpha * 0.9f)),
                    bounds.top,
                    bounds.bottom,
                ),
            )
        }
        if (condition == WeatherCondition.STORM) {
            drawPath(bolt, Color(0x33FFF6D0), style = Stroke(7 * dp, cap = StrokeCap.Round))
            drawPath(bolt, Color(0xCCFFFBEA), style = Stroke(1.2f * dp, cap = StrokeCap.Round))
        }

        drawPath(far, farColor)
        drawPath(mid, midColor)

        // Lac (sous l'horizon) et ses reflets.
        drawRect(Brush.verticalGradient(listOf(lakeTop, lakeBottom), horizon, size.height), Offset(0f, horizon))
        drawPath(reflection, midColor.copy(alpha = 0.35f))
        shimmer.forEachIndexed { i, (a, b) ->
            drawLine(sunColor.copy(alpha = 0.5f * lightVisible * (1f - i / 16f)), a, b, 1.4f * dp, StrokeCap.Round)
        }
        drawPath(near, nearColor)
        drawPath(pines, nearColor)

        fogBands.forEach { (y, a) ->
            val center = size.height * y
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xFFDCE3EA).copy(alpha = a), Color.Transparent),
                    center - size.height * 0.06f,
                    center + size.height * 0.06f,
                ),
                Offset(0f, center - size.height * 0.06f),
                Size(size.width, size.height * 0.12f),
            )
        }

        if (drops > 0) {
            rain.forEach { (p, a) ->
                drawLine(Color.White.copy(alpha = a), p, p + Offset(-dropLength * 0.22f, dropLength), 1.1f * dp, StrokeCap.Round)
            }
            ripples.forEach { p ->
                drawOval(
                    Color.White.copy(alpha = 0.22f),
                    p - Offset(9 * dp, 2 * dp),
                    Size(18 * dp, 4 * dp),
                    style = Stroke(0.8f * dp),
                )
            }
        }
        flakes.forEach { (p, r, a) -> drawCircle(Color.White.copy(alpha = a), r * dp, p) }

        // Haut et bas assombris : texte lisible sur un ciel clair et sous les tuiles.
        drawRect(Brush.verticalGradient(listOf(Color(0x40000000), Color.Transparent), 0f, size.height * 0.3f))
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Color(0x73000000)), size.height * 0.55f, size.height),
            Offset(0f, size.height * 0.55f),
        )
    }
}

/** Crête montagneuse aléatoire mais stable (graine), fermée jusqu'à [base]. */
private fun ridge(w: Float, base: Float, maxHeight: Float, minHeight: Float, seed: Int, peaks: Int): Path {
    val rnd = Random(seed)
    val points = (0..peaks * 2).map { i ->
        val x = w * i / (peaks * 2f) + (rnd.nextFloat() - 0.5f) * w / (peaks * 4f)
        val height = if (i % 2 == 0) minHeight + rnd.nextFloat() * (maxHeight - minHeight) * 0.35f
        else minHeight + (0.55f + rnd.nextFloat() * 0.45f) * (maxHeight - minHeight)
        Offset(x.coerceIn(0f, w), base - height)
    }
    return Path().apply {
        moveTo(0f, base)
        lineTo(0f, points.first().y)
        points.zipWithNext().forEach { (a, b) ->
            // Courbes douces : quadratique vers le milieu de chaque segment.
            quadraticTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
        }
        lineTo(w, points.last().y)
        lineTo(w, base)
        close()
    }
}

/** Pin stylisé : trois étages triangulaires sur un tronc fin. */
private fun addPine(path: Path, x: Float, ground: Float, height: Float) {
    val width = height * 0.42f
    for (tier in 0 until 3) {
        val top = ground - height + tier * height * 0.22f
        val bottom = top + height * 0.42f
        val half = width / 2 * (0.6f + tier * 0.25f)
        path.moveTo(x, top)
        path.lineTo(x + half, bottom)
        path.lineTo(x - half, bottom)
        path.close()
    }
    path.addRect(Rect(x - height * 0.025f, ground - height * 0.15f, x + height * 0.025f, ground))
}

/** Nuage cotonneux : ovales réunis en un seul tracé (pas de recouvrement visible). */
private fun cloud(x: Float, y: Float, width: Float): Pair<Path, Rect> {
    val path = Path()
    listOf(
        Triple(-0.32f, 0.06f, 0.17f),
        Triple(-0.12f, -0.05f, 0.24f),
        Triple(0.12f, -0.1f, 0.27f),
        Triple(0.34f, 0.02f, 0.19f),
        Triple(0.0f, 0.08f, 0.22f),
    ).forEach { (dx, dy, r) ->
        val center = Offset(x + dx * width, y + dy * width * 0.5f)
        path.addOval(Rect(center - Offset(r * width, r * width * 0.42f), Size(2 * r * width, 0.84f * r * width)))
    }
    return path to path.getBounds()
}

/** Symétrie verticale autour de la ligne y = [axis] (reflet dans le lac). */
private fun mirror(axis: Float) = androidx.compose.ui.graphics.Matrix().apply {
    translate(0f, 2 * axis)
    scale(1f, -1f)
}

