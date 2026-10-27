package fr.jbrunet.yama.data.location

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
import fr.jbrunet.yama.R
import fr.jbrunet.yama.domain.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Position through the native LocationManager (no Google Play Services → F-Droid compatible). */
class LocationProvider(private val context: Context) {

    private val manager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Current named position (nearest city if the system geocoder is available).
     * [fresh] false (background refresh): last known position only, without turning on
     * the GPS — Android refuses it to a background app anyway, and the wait
     * (up to 10 s) cost battery every 15 min.
     */
    suspend fun currentPlace(fresh: Boolean = true): Place? {
        val location = (if (fresh) currentLocation() else lastKnownLocation()) ?: return null
        val name = withContext(Dispatchers.IO) { cityName(location) } ?: context.getString(R.string.my_location)
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

        /** Detail of the places coming from the position (rather than picked from the list). */
        const val CURRENT_DETAIL = "current-location"
    }

    /**
     * City name, remembered for the last area (~1 km): the system geocoder may query
     * the network, no need to do it again on every refresh without having moved.
     */
    @Suppress("DEPRECATION")
    private fun cityName(location: Location): String? {
        val prefs = context.getSharedPreferences("location", Context.MODE_PRIVATE)
        val area = "%.2f,%.2f".format(Locale.ROOT, location.latitude, location.longitude)
        if (prefs.getString("area", null) == area) prefs.getString("city", null)?.let { return it }
        if (!Geocoder.isPresent()) return null
        return runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()?.let { it.locality ?: it.subAdminArea }
        }.getOrNull()?.also { prefs.edit().putString("area", area).putString("city", it).apply() }
    }
}
