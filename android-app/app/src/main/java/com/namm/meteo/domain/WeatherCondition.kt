package com.namm.meteo.domain

/** Regroupement des codes WMO renvoyés par Open-Meteo. */
enum class WeatherCondition(val label: String) {
    CLEAR("Ensoleillé"),
    PARTLY_CLOUDY("Éclaircies"),
    CLOUDY("Couvert"),
    FOG("Brouillard"),
    DRIZZLE("Bruine"),
    RAIN("Pluie"),
    SNOW("Neige"),
    STORM("Orage");

    companion object {
        fun fromWmo(code: Int): WeatherCondition = when (code) {
            0 -> CLEAR
            1, 2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..57 -> DRIZZLE
            in 61..67, in 80..82 -> RAIN
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> STORM
            else -> CLOUDY
        }
    }
}
