package com.namm.meteo.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.namm.meteo.domain.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Position via le LocationManager natif (pas de Google Play Services → compatible F-Droid). */
class LocationProvider(private val context: Context) {

    private val manager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Position actuelle nommée (ville la plus proche si le géocodeur système est disponible).
     * [fresh] faux (actualisation en arrière-plan) : dernière position connue seulement, sans
     * allumer le GPS — Android le refuse de toute façon à une appli en arrière-plan et l'attente
     * (jusqu'à 10 s) coûtait de la batterie toutes les 15 min.
     */
    suspend fun currentPlace(fresh: Boolean = true): Place? {
        val location = (if (fresh) currentLocation() else lastKnownLocation()) ?: return null
        val name = withContext(Dispatchers.IO) { cityName(location) } ?: "Ma position"
        return Place(name, location.latitude, location.longitude, CURRENT_DETAIL)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        if (!hasPermission()) return null
        return manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? {
        if (!hasPermission()) return null
        val providers = manager.getProviders(true)
        val provider = when {
            LocationManager.FUSED_PROVIDER in providers && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                LocationManager.FUSED_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        val fresh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
                }
            }
        } else {
            null
        }
        return fresh ?: lastKnownLocation()
    }

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        /** Détail des lieux issus de la position (et non choisis dans la liste). */
        const val CURRENT_DETAIL = "Position actuelle"
    }

    /**
     * Nom de la ville, mémorisé pour la dernière zone (~1 km) : le géocodeur système peut
     * interroger le réseau, inutile de le refaire à chaque actualisation sans avoir bougé.
     */
    @Suppress("DEPRECATION")
    private fun cityName(location: Location): String? {
        val prefs = context.getSharedPreferences("location", Context.MODE_PRIVATE)
        val area = "%.2f,%.2f".format(Locale.ROOT, location.latitude, location.longitude)
        if (prefs.getString("area", null) == area) prefs.getString("city", null)?.let { return it }
        if (!Geocoder.isPresent()) return null
        return runCatching {
            Geocoder(context, Locale.FRENCH).getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()?.let { it.locality ?: it.subAdminArea }
        }.getOrNull()?.also { prefs.edit().putString("area", area).putString("city", it).apply() }
    }
}
