package fr.jbrunet.yama.domain

import kotlinx.serialization.Serializable

@Serializable
data class Place(val name: String, val latitude: Double, val longitude: Double, val detail: String? = null) {
    /** Same place within ~100 m (avoids duplicates in the list). */
    fun sameAs(other: Place) =
        kotlin.math.abs(latitude - other.latitude) < 0.001 && kotlin.math.abs(longitude - other.longitude) < 0.001

    companion object {
        val DEFAULT = Place("Paris", 48.8566, 2.3522, "Île-de-France, France")
    }
}
