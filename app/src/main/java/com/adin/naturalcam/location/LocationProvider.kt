package com.adin.naturalcam.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Location is only consulted when the user enabled geotagging (AGENTS 53);
 * the permission check lives here so no caller can bypass it.
 */
interface LocationProvider {
    /** Last known coarse position, or null when unavailable / not permitted. */
    fun lastKnownLocation(): Pair<Double, Double>?
}

class AndroidLocationProvider(private val context: Context) : LocationProvider {

    override fun lastKnownLocation(): Pair<Double, Double>? {
        val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = manager.getProviders(true).firstOrNull() ?: return null
        @Suppress("MissingPermission") // guarded above
        val location = manager.getLastKnownLocation(provider) ?: return null
        return location.latitude to location.longitude
    }
}
