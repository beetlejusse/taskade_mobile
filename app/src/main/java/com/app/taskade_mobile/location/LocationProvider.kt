package com.app.taskade_mobile.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

/** A resolved place: "City, Country" plus the device timezone. */
data class ResolvedPlace(val label: String, val timezone: String)

/**
 * Resolves the device location once and reverse-geocodes it to "City, Country"
 * for profile context (PRD §7). Mirrors the web client: a single coarse fix, no
 * third-party key (uses the platform [Geocoder]).
 *
 * Returns null when permission is missing or no fix/geocode is available — the
 * caller should treat location as simply unavailable, never block on it.
 */
class LocationProvider(context: Context) {

    private val appContext = context.applicationContext
    private val fused = LocationServices.getFusedLocationProviderClient(appContext)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun resolve(): ResolvedPlace? {
        if (!hasPermission()) return null
        val location = currentLocation() ?: return null
        val label = reverseGeocode(location) ?: return null
        return ResolvedPlace(label, TimeZone.getDefault().id)
    }

    @Suppress("MissingPermission") // guarded by hasPermission()
    private suspend fun currentLocation(): Location? =
        suspendCancellableCoroutine { cont ->
            val cts = CancellationTokenSource()
            fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener {
                    Log.w(TAG, "Location fetch failed", it)
                    cont.resume(null)
                }
            cont.invokeOnCancellation { cts.cancel() }
        }

    private suspend fun reverseGeocode(location: Location): String? = withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION") // sync API kept for minSdk 24; async variant is API 33+
            val results = Geocoder(appContext, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
            val address = results?.firstOrNull() ?: return@runCatching null
            val city = address.locality
                ?: address.subAdminArea
                ?: address.adminArea
            val country = address.countryName
            when {
                city != null && country != null -> "$city, $country"
                country != null -> country
                else -> null
            }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "LocationProvider"
    }
}
