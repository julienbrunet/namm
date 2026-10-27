package fr.jbrunet.yama.domain

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/** Moment de la journée, pour le décor de l'accueil. */
enum class DayPhase { NIGHT, DAWN, DAY, DUSK }

/**
 * Position du soleil : [progress] vaut 0 au lever et 1 au coucher (hors de [0, 1] la nuit).
 * L'aube et le crépuscule couvrent 45 min de part et d'autre du lever et du coucher.
 */
data class SunPosition(val phase: DayPhase, val progress: Float)

private val TWILIGHT: Duration = Duration.ofMinutes(45)

/**
 * Heures ISO locales (« 2026-10-16T19:45 ») ; sans lever ni coucher connus (ancien cache),
 * on suppose 7 h – 19 h.
 */
fun sunPosition(time: String, sunrise: String?, sunset: String?): SunPosition {
    val now = LocalDateTime.parse(time)
    val rise = sunrise?.let(LocalDateTime::parse) ?: now.toLocalDate().atTime(LocalTime.of(7, 0))
    val set = sunset?.let(LocalDateTime::parse) ?: now.toLocalDate().atTime(LocalTime.of(19, 0))
    val day = Duration.between(rise, set).toMinutes().coerceAtLeast(1)
    val progress = Duration.between(rise, now).toMinutes().toFloat() / day
    val phase = when {
        now.isBefore(rise.minus(TWILIGHT)) || now.isAfter(set.plus(TWILIGHT)) -> DayPhase.NIGHT
        now.isBefore(rise.plus(TWILIGHT)) -> DayPhase.DAWN
        now.isAfter(set.minus(TWILIGHT)) -> DayPhase.DUSK
        else -> DayPhase.DAY
    }
    return SunPosition(phase, progress)
}
