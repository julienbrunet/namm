package fr.jbrunet.yama.domain

/** Threshold (mm over 15 min) above which it is considered to be raining. */
const val RAIN_THRESHOLD_MM = 0.1

/**
 * Minutes before the rain starts, if it starts within the given horizon
 * while it is not raining at the first step; null otherwise.
 */
fun minutesUntilRain(steps: List<PrecipitationStep>): Int? {
    if (steps.isEmpty() || steps.first().millimeters >= RAIN_THRESHOLD_MM) return null
    val index = steps.indexOfFirst { it.millimeters >= RAIN_THRESHOLD_MM }
    return if (index > 0) index * 15 else null
}
