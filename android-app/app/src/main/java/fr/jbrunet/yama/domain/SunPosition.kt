package fr.jbrunet.yama.domain

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/** Time of day, for the home screen scenery. */
enum class DayPhase { NIGHT, DAWN, DAY, DUSK }

/**
 * Sun position: [progress] is 0 at sunrise and 1 at sunset (outside [0, 1] at night).
 * Dawn and dusk cover 45 min on either side of sunrise and sunset.
 */
data class SunPosition(val phase: DayPhase, val progress: Float)

private val TWILIGHT: Duration = Duration.ofMinutes(45)

/**
 * Local ISO times (“2026-10-16T19:45”); without known sunrise or sunset (old cache),
 * 7 h – 19 h is assumed.
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
