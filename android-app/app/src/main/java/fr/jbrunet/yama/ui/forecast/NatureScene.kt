package fr.jbrunet.yama.ui.forecast

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import fr.jbrunet.yama.domain.DayPhase
import fr.jbrunet.yama.domain.SunPosition
import fr.jbrunet.yama.domain.WeatherCondition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Paysage zen calculé pixel par pixel (aucune image embarquée) : ciel et lumière selon l'heure,
 * nuages en texture fractale vus en perspective, deux chaînes de montagnes dans la brume, lac
 * qui reflète le tout. L'image, en basse définition, est agrandie avec lissage : le flou fait
 * partie du rendu. Seules la pluie fine, la neige et les étoiles sont dessinées par-dessus.
 */
@Composable
fun NatureScene(condition: WeatherCondition, sun: SunPosition, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val height = (SCENE_WIDTH * size.height / size.width.coerceAtLeast(1f)).roundToInt().coerceIn(1, 4 * SCENE_WIDTH)
            val renderer = SceneRenderer(condition, sun)
            val image = SceneCache.get(renderer, SCENE_WIDTH, height)
            val overlay = SceneOverlay(condition, sun, renderer, size.width, size.height, density)
            onDrawBehind {
                drawImage(
                    image,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Low, // bilinéaire : flou doux, pas de pixels
                )
                overlay.draw(this)
                // Haut et bas assombris : texte lisible sur un ciel clair et sous les tuiles.
                drawRect(Brush.verticalGradient(listOf(Color(0x47000000), Color.Transparent), 0f, size.height * 0.32f))
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0x80000000)), size.height * 0.6f, size.height),
                    Offset(0f, size.height * 0.6f),
                )
            }
        },
    )
}

/** Largeur de l'image calculée (pixels) ; la hauteur suit les proportions de l'écran. */
internal const val SCENE_WIDTH = 300

/** Ligne d'horizon (rive du lac), en fraction de la hauteur. */
internal const val HORIZON = 0.62f

/** Dernière image calculée : pas de nouveau rendu en revenant de la carte ou des paramètres. */
private object SceneCache {
    private var last: Pair<Any, ImageBitmap>? = null

    fun get(renderer: SceneRenderer, width: Int, height: Int): ImageBitmap {
        val key = Triple(renderer.key, width, height)
        last?.takeIf { it.first == key }?.let { return it.second }
        val bitmap = Bitmap.createBitmap(renderer.render(width, height), width, height, Bitmap.Config.ARGB_8888)
        return bitmap.asImageBitmap().also { last = key to it }
    }
}

/** Pluie fine (traits flous), neige (flocons au bokeh) et étoiles, nettes par-dessus l'image. */
private class SceneOverlay(
    condition: WeatherCondition,
    sun: SunPosition,
    renderer: SceneRenderer,
    w: Float,
    h: Float,
    private val dp: Float,
) {
    private val rnd = Random(5)
    private val rain = when (condition) {
        WeatherCondition.RAIN -> 70
        WeatherCondition.STORM -> 110
        WeatherCondition.DRIZZLE -> 35
        else -> 0
    }.let { n -> List(n) { Offset(rnd.nextFloat() * w * 1.1f, rnd.nextFloat() * h * (HORIZON + 0.02f)) to rnd.nextFloat() } }
    private val dropLength = h * if (condition == WeatherCondition.DRIZZLE) 0.025f else 0.06f
    private val flakes = if (condition == WeatherCondition.SNOW) {
        List(70) { Offset(rnd.nextFloat() * w, rnd.nextFloat() * h) to rnd.nextFloat() }
    } else emptyList()
    private val stars = if (sun.phase == DayPhase.NIGHT) {
        List(90) { Offset(rnd.nextFloat(), rnd.nextFloat() * (HORIZON - 0.12f)) to rnd.nextFloat() }
            // Seulement là où le ciel est dégagé.
            .mapNotNull { (p, a) ->
                val clear = 1f - renderer.cloudDensity(p.x, p.y)
                if (clear > 0.3f) Offset(p.x * w, p.y * h) to a * clear else null
            }
    } else emptyList()

    fun draw(scope: DrawScope) = with(scope) {
        stars.forEach { (p, a) -> drawCircle(Color.White.copy(alpha = 0.25f + 0.55f * a), (0.5f + a * 0.7f) * dp, p) }
        rain.forEach { (p, a) ->
            val end = p + Offset(-dropLength * 0.12f, dropLength)
            drawLine(
                Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.06f + 0.1f * a), Color.Transparent), p, end),
                p, end, 1f * dp, StrokeCap.Round,
            )
        }
        flakes.forEach { (p, a) ->
            // Quelques gros flocons flous au premier plan, beaucoup de petits au loin.
            val r = (1.2f + a * a * 5f) * dp
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.75f - a * 0.35f), Color.Transparent), p, r),
                r,
                p,
            )
        }
    }
}

/** Couleur RVB en flottants (0..1). */
private class Rgb(val r: Float, val g: Float, val b: Float) {
    fun mix(o: Rgb, t: Float) = Rgb(r + (o.r - r) * t, g + (o.g - g) * t, b + (o.b - b) * t)
    operator fun times(k: Float) = Rgb(r * k, g * k, b * k)

    /** Vers le gris de même luminosité (temps couvert). */
    fun grey(t: Float): Rgb {
        val l = 0.3f * r + 0.59f * g + 0.11f * b
        return mix(Rgb(l, l, l * 1.03f), t)
    }
}

private fun rgb(r: Float, g: Float, b: Float) = Rgb(r, g, b)

/** Palette d'un moment de la journée. */
private class Palette(
    val zenith: Rgb, val mid: Rgb, val horizon: Rgb, val light: Rgb,
    val cloudLit: Rgb, val cloudShade: Rgb, val far: Rgb, val near: Rgb, val water: Rgb,
)

private fun palette(phase: DayPhase) = when (phase) {
    DayPhase.DAY -> Palette(
        rgb(0.18f, 0.36f, 0.6f), rgb(0.4f, 0.58f, 0.78f), rgb(0.76f, 0.84f, 0.9f), rgb(1f, 0.97f, 0.88f),
        rgb(0.97f, 0.97f, 0.98f), rgb(0.56f, 0.6f, 0.68f), rgb(0.33f, 0.41f, 0.52f), rgb(0.1f, 0.14f, 0.15f), rgb(0.1f, 0.2f, 0.27f),
    )
    DayPhase.DAWN -> Palette(
        rgb(0.15f, 0.19f, 0.36f), rgb(0.5f, 0.47f, 0.6f), rgb(0.97f, 0.76f, 0.6f), rgb(1f, 0.86f, 0.68f),
        rgb(1f, 0.82f, 0.74f), rgb(0.42f, 0.38f, 0.5f), rgb(0.31f, 0.3f, 0.42f), rgb(0.08f, 0.08f, 0.12f), rgb(0.13f, 0.13f, 0.22f),
    )
    DayPhase.DUSK -> Palette(
        rgb(0.11f, 0.13f, 0.3f), rgb(0.48f, 0.35f, 0.5f), rgb(0.99f, 0.62f, 0.42f), rgb(1f, 0.74f, 0.48f),
        rgb(1f, 0.68f, 0.55f), rgb(0.33f, 0.26f, 0.38f), rgb(0.27f, 0.21f, 0.34f), rgb(0.07f, 0.06f, 0.1f), rgb(0.11f, 0.09f, 0.18f),
    )
    DayPhase.NIGHT -> Palette(
        rgb(0.02f, 0.03f, 0.08f), rgb(0.05f, 0.07f, 0.16f), rgb(0.12f, 0.15f, 0.25f), rgb(0.86f, 0.88f, 0.95f),
        rgb(0.2f, 0.22f, 0.3f), rgb(0.06f, 0.07f, 0.11f), rgb(0.08f, 0.1f, 0.16f), rgb(0.02f, 0.03f, 0.05f), rgb(0.03f, 0.04f, 0.08f),
    )
}

/**
 * Calcul du paysage. [render] renvoie des pixels ARGB ; (u, v) sont les coordonnées
 * normalisées (0..1) de l'écran, v vers le bas.
 */
internal class SceneRenderer(private val condition: WeatherCondition, sun: SunPosition) {
    private val phase = sun.phase
    private val night = phase == DayPhase.NIGHT
    private val progress = sun.progress.coerceIn(0f, 1f)

    /** Identité du rendu (position du soleil arrondie : une image par quart d'heure environ). */
    val key: Any = Triple(condition, phase, (progress * 50).roundToInt())

    /** Part du ciel couverte (0 = dégagé, 1 = couvert uniforme). */
    private val coverage = when (condition) {
        WeatherCondition.CLEAR -> 0f
        WeatherCondition.PARTLY_CLOUDY -> 0.42f
        WeatherCondition.CLOUDY -> 0.85f
        WeatherCondition.FOG -> 0.75f
        WeatherCondition.DRIZZLE, WeatherCondition.SNOW -> 0.9f
        WeatherCondition.RAIN -> 0.96f
        WeatherCondition.STORM -> 1f
    }
    private val gloom = when (condition) {
        WeatherCondition.RAIN -> 0.22f
        WeatherCondition.STORM -> 0.45f
        WeatherCondition.DRIZZLE, WeatherCondition.CLOUDY -> 0.1f
        else -> 0f
    } * if (night) 0.3f else 1f // la nuit est déjà sombre
    private val wet = condition == WeatherCondition.RAIN || condition == WeatherCondition.STORM ||
        condition == WeatherCondition.DRIZZLE

    private val p: Palette = palette(phase).let { night(it) }.let { base ->
        val g = ((coverage - 0.3f).coerceAtLeast(0f) * 1.1f).coerceAtMost(0.85f) // ternir seulement par temps couvert
        fun Rgb.weather() = grey(g) * (1f - gloom)
        Palette(
            base.zenith.weather(), base.mid.weather(), base.horizon.weather(), base.light,
            base.cloudLit.weather(), base.cloudShade.weather() * (1f - gloom * 0.6f),
            base.far.weather().let { if (condition == WeatherCondition.SNOW) it.mix(rgb(0.78f, 0.8f, 0.84f), 0.35f) else it },
            base.near.weather(), base.water.weather(),
        )
    }

    /** Nuit couverte : les nuages renvoient une lueur grise au lieu d'un ciel noir. */
    private fun night(base: Palette): Palette {
        if (!night || coverage <= 0.5f) return base
        val t = (coverage - 0.5f) * 1.6f
        return Palette(
            base.zenith.mix(rgb(0.09f, 0.1f, 0.13f), t), base.mid.mix(rgb(0.13f, 0.14f, 0.18f), t),
            base.horizon.mix(rgb(0.22f, 0.23f, 0.27f), t), base.light,
            base.cloudLit.mix(rgb(0.24f, 0.25f, 0.3f), t), base.cloudShade.mix(rgb(0.1f, 0.11f, 0.14f), t),
            base.far.mix(rgb(0.12f, 0.13f, 0.17f), t), base.near, base.water,
        )
    }

    // Soleil sur un arc à droite (loin du texte) ; lune fixe la nuit.
    private val lightU = if (night) 0.78f else 0.58f + 0.32f * progress
    private val lightV = if (night) 0.16f else HORIZON - (0.14f + 0.2f * sin(PI * progress).toFloat())
    private val lightStrength = when (phase) {
        DayPhase.DAY -> 0.8f
        DayPhase.DAWN, DayPhase.DUSK -> 1f
        DayPhase.NIGHT -> 0.45f
    }
    private val mist = when {
        condition == WeatherCondition.FOG -> 0.9f
        phase == DayPhase.DAWN -> 0.65f
        wet -> 0.5f
        else -> 0.25f
    }

    private var ridgeFar = FloatArray(0)
    private var ridgeNear = FloatArray(0)
    private var pixel = 0f

    fun render(width: Int, height: Int): IntArray {
        pixel = 1f / height
        // Crêtes : une hauteur par colonne (bruit fractal), sommets doux, pas de plateaux.
        ridgeFar = FloatArray(width) { x ->
            val u = (x + 0.5f) / width
            HORIZON - 0.03f - 0.19f * ((fbm(u * 1.9f, 1.7f, 6, 3) - 0.3f) / 0.42f).coerceIn(0f, 1f)
        }
        ridgeNear = FloatArray(width) { x ->
            val u = (x + 0.5f) / width
            HORIZON - 0.008f - 0.055f * ((fbm(u * 3.2f + 11f, 4.2f, 6, 9, gain = 0.42f) - 0.3f) / 0.42f).coerceIn(0f, 1f)
        }
        val aspect = height / width.toFloat()
        return IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val u = (x + 0.5f) / width
            val v = (y + 0.5f) / height
            var c = if (v < HORIZON) land(u, v, x, aspect) else lake(u, v, aspect)
            // Rideaux de pluie : voiles verticaux devant le paysage.
            if (wet) {
                val veil = smooth(0.48f, 0.8f, fbm(u * 16f, v * 1.4f + 3f, 3, 21)) * smooth(0.08f, 0.5f, v)
                c = c.mix(p.cloudShade.mix(p.horizon, 0.4f), veil * if (condition == WeatherCondition.DRIZZLE) 0.18f else 0.32f)
            }
            if (condition == WeatherCondition.FOG) {
                c = c.mix(p.horizon.mix(rgb(0.85f, 0.87f, 0.9f), 0.35f), 0.35f + 0.4f * exp(-abs(v - HORIZON) / 0.14f))
            }
            argb(c, x, y)
        }
    }

    /** Ciel, nuages et montagnes au-dessus de l'horizon. */
    private fun land(u: Float, v: Float, x: Int, aspect: Float): Rgb {
        var c = sky(u, v, aspect)
        val column = x.coerceIn(0, ridgeFar.size - 1)
        // Bords lissés sur un pixel (l'image est agrandie ensuite).
        val farTop = ridgeFar[column]
        val farCover = ((v - farTop) / pixel + 0.5f).coerceIn(0f, 1f)
        if (farCover > 0f) {
            val tex = fbm(u * 22f, v * 30f, 3, 31)
            var rock = p.far * (0.88f + 0.24f * tex)
            if (condition == WeatherCondition.SNOW) {
                rock = rock.mix(rgb(0.86f, 0.88f, 0.92f) * (1f - gloom), smooth(0.05f, 0.0f, v - farTop + (tex - 0.5f) * 0.05f))
            }
            // Perspective aérienne : plus on descend vers la vallée, plus la brume l'emporte.
            c = c.mix(rock.mix(p.horizon, 0.4f + 0.42f * smooth(HORIZON - 0.22f, HORIZON, v)), farCover)
        }
        val nearTop = ridgeNear[column]
        val nearCover = ((v - nearTop) / pixel + 0.5f).coerceIn(0f, 1f)
        if (nearCover > 0f) {
            // Texture fine : une forêt vue de loin.
            val forest = fbm(u * 70f, v * 55f, 3, 41)
            c = c.mix((p.near * (0.8f + 0.4f * forest)).mix(p.horizon, 0.14f + 0.3f * smooth(HORIZON - 0.06f, HORIZON, v)), nearCover)
        }
        // Brume de vallée posée sur l'eau.
        return c.mix(p.horizon.mix(rgb(0.9f, 0.9f, 0.92f), 0.2f), mist * exp(-(HORIZON - v) / 0.03f) * 0.7f)
    }

    /** Lac : le paysage reflété, troublé par de petites vagues, assombri vers le bas. */
    private fun lake(u: Float, v: Float, aspect: Float): Rgb {
        val depth = (v - HORIZON) / (1f - HORIZON)
        val chop = if (wet) 2.2f else 1f
        val wave = fbm(u * 3f, v * 110f, 3, 51) - 0.5f
        val ru = (u + wave * 0.012f * chop * (0.3f + depth)).coerceIn(0f, 0.999f)
        val rv = (2 * HORIZON - v + wave * 0.006f * chop).coerceIn(0f, HORIZON - 0.0001f)
        var c = land(ru, rv, (ru * ridgeFar.size).toInt(), aspect)
        c = c.mix(p.water, 0.25f + 0.4f * depth) * (0.88f - 0.3f * depth)
        // Scintillement du soleil (ou de la lune) sur l'eau.
        val spread = 0.025f + 0.09f * depth
        val column = exp(-((u - lightU) / spread).let { it * it })
        val sparkle = smooth(0.55f, 0.8f, fbm(u * 24f, v * 160f, 2, 61))
        val visible = 1f - coverage * 0.9f
        return c.mix(p.light, (column * sparkle * 0.6f * visible * lightStrength).coerceAtMost(1f))
    }

    private fun sky(u: Float, v: Float, aspect: Float): Rgb {
        val t = v / HORIZON
        var c = if (t < 0.55f) p.zenith.mix(p.mid, smooth(0f, 0.55f, t)) else p.mid.mix(p.horizon, smooth(0.55f, 1f, t))
        // Diffusion autour de l'astre : cœur net, halo large qui traverse les nuages.
        val dx = u - lightU
        val dy = (v - lightV) * aspect
        val d = sqrt(dx * dx + dy * dy)
        // Disque net (soleil ou lune) entouré d'une lueur serrée.
        val radius = if (night) 0.02f else 0.026f
        val core = (smooth(radius * 1.25f, radius * 0.8f, d) + exp(-(d * d) / 0.004f) * 0.35f).coerceAtMost(1f)
        val halo = (exp(-d / 0.1f) * 0.4f + exp(-d / 0.35f) * 0.22f) * lightStrength
        // Lueur sur l'horizon à l'aube et au crépuscule, du côté du soleil.
        val horizonGlow = if (phase == DayPhase.DAWN || phase == DayPhase.DUSK) {
            exp(-(HORIZON - v) / 0.12f) * 0.35f * (1f - 0.7f * abs(u - lightU)).coerceAtLeast(0f)
        } else 0f
        val density = cloudDensity(u, v)
        c = c.mix(p.light, ((halo + horizonGlow) * (1f - coverage * 0.55f)).coerceAtMost(1f))
        c = c.mix(p.light, (core * (1f - density) * (1f - coverage * 0.7f)).coerceAtMost(1f))
        if (density > 0f) {
            val thick = smooth(0.35f, 1f, density + cloudNoise(u, v) * 0.4f)
            var cloud = p.cloudLit.mix(p.cloudShade, thick * 0.75f)
            // Bords éclairés près du soleil.
            cloud = cloud.mix(p.light, (halo * 1.2f * (1f - thick)).coerceAtMost(0.8f))
            c = c.mix(cloud, density * 0.96f)
        }
        return c
    }

    /** Bruit des nuages sur un plan vu en perspective : grands près du zénith, serrés vers l'horizon. */
    private fun cloudNoise(u: Float, v: Float): Float {
        val distance = 1f / ((HORIZON - v).coerceAtLeast(0f) + 0.06f)
        return fbm((u - 0.5f) * distance * 0.9f + 7f, distance * 1.1f, 5, 71)
    }

    /** Opacité des nuages en (u, v), 0..1 (sert aussi à cacher les étoiles). */
    fun cloudDensity(u: Float, v: Float): Float {
        if (v >= HORIZON) return 0f
        val fade = smooth(0f, 0.1f, HORIZON - v)
        if (coverage == 0f) {
            // Ciel dégagé : quelques cirrus étirés, à peine visibles.
            val distance = 1f / ((HORIZON - v).coerceAtLeast(0f) + 0.06f)
            return smooth(0.6f, 0.8f, fbm((u - 0.5f) * distance * 0.35f, distance * 2.2f, 4, 81)) * 0.3f * fade
        }
        val n = cloudNoise(u, v)
        // Seuil choisi pour que la part de ciel couverte suive [coverage] (bruit ≈ 0,5 ± 0,12).
        val z = (n - 0.5f) / 0.12f
        val threshold = (0.5f - coverage) * 2.6f
        val overcast = ((coverage - 0.75f) / 0.25f).coerceIn(0f, 1f) * 0.85f
        return maxOf(smooth(threshold - 0.5f, threshold + 1f, z), overcast * (0.75f + 0.5f * n).coerceAtMost(1f)) * fade
    }

    /** Pixel ARGB avec un léger grain (évite les bandes dans les dégradés). */
    private fun argb(c: Rgb, x: Int, y: Int): Int {
        val grain = (hash(x, y, 97) - 0.5f) * 0.012f
        fun channel(f: Float) = ((f + grain).coerceIn(0f, 1f) * 255f).roundToInt()
        return (0xFF shl 24) or (channel(c.r) shl 16) or (channel(c.g) shl 8) or channel(c.b)
    }
}

private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

private fun hash(x: Int, y: Int, seed: Int): Float {
    var h = x * 374761393 + y * 668265263 + seed * 1442695041
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFFFF) / 16777215f
}

/** Bruit de valeur lissé. */
private fun noise(x: Float, y: Float, seed: Int): Float {
    val xi = floor(x).toInt()
    val yi = floor(y).toInt()
    val xf = x - xi
    val yf = y - yi
    val u = xf * xf * (3 - 2 * xf)
    val v = yf * yf * (3 - 2 * yf)
    val top = hash(xi, yi, seed).let { it + (hash(xi + 1, yi, seed) - it) * u }
    val bottom = hash(xi, yi + 1, seed).let { it + (hash(xi + 1, yi + 1, seed) - it) * u }
    return top + (bottom - top) * v
}

/** Bruit fractal (somme d'octaves), normalisé dans 0..1. */
private fun fbm(x: Float, y: Float, octaves: Int, seed: Int, gain: Float = 0.5f): Float {
    var sum = 0f
    var amplitude = 0.5f
    var frequency = 1f
    var norm = 0f
    for (o in 0 until octaves) {
        sum += amplitude * noise(x * frequency, y * frequency, seed + o * 17)
        norm += amplitude
        amplitude *= gain
        frequency *= 2.03f
    }
    return sum / norm
}
