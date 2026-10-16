package com.namm.meteo.domain

/** Version « majeur.mineur.patch » comparée numériquement (0.1.9 < 0.1.10 < 0.2.0). */
data class AppVersion(val parts: List<Int>) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val diff = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    override fun toString() = parts.joinToString(".")

    companion object {
        /** Accepte "v0.2.5", "0.2.5", "No Ad Météo 0.2.5"… ; null si illisible. */
        fun parse(text: String): AppVersion? {
            val match = Regex("""(\d+)(\.\d+)*""").find(text) ?: return null
            return AppVersion(match.value.split('.').map { it.toInt() })
        }
    }
}
