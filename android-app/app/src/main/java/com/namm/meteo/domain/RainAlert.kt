package com.namm.meteo.domain

/** Seuil (mm sur 15 min) au-delà duquel on considère qu'il pleut. */
const val RAIN_THRESHOLD_MM = 0.1

/**
 * Minutes avant le début de la pluie, si elle commence dans l'horizon fourni
 * alors qu'il ne pleut pas au premier pas ; null sinon.
 */
fun minutesUntilRain(steps: List<PrecipitationStep>): Int? {
    if (steps.isEmpty() || steps.first().millimeters >= RAIN_THRESHOLD_MM) return null
    val index = steps.indexOfFirst { it.millimeters >= RAIN_THRESHOLD_MM }
    return if (index > 0) index * 15 else null
}
