package com.geovault.tracker.services

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat

class LocationSessionCoordinator(
    private val context: Context,
    private val locationManager: LocationManager
) {
    fun startGpsSession(
        intervalMs: Long,
        minDistanceMeters: Float,
        listener: LocationListener
    ) {
        val executor = ContextCompat.getMainExecutor(context)
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            intervalMs,
            minDistanceMeters,
            executor,
            listener
        )
    }

    fun stopGpsSession(listener: LocationListener) {
        runCatching { locationManager.removeUpdates(listener) }
    }

    fun isGpsProviderEnabled(): Boolean {
        return runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)
    }

    fun lastKnownGpsLocation(): Location? {
        return runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
            .getOrNull()
    }
}
