package com.geovault.tracker.services

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import com.geovault.tracker.location.UnifiedLocationClient
import com.geovault.tracker.location.UnifiedLocationSessionRequest
import com.google.android.gms.location.LocationRequest

class LocationSessionCoordinator(
    context: Context
) {
    private companion object {
        private const val TAG = "LocationSessionCoordinator"
    }

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val unifiedLocationClient = UnifiedLocationClient(appContext)
    private var lastDeliveredLocation: Location? = null

    fun startSession(
        request: LocationRequest,
        listener: LocationListener
    ): Boolean {
        if (!unifiedLocationClient.hasLocationPermission()) return false
        return unifiedLocationClient.startSession(
            sessionRequest = UnifiedLocationSessionRequest(request),
            onLocation = { location ->
                val snapshot = Location(location)
                lastDeliveredLocation = snapshot
                listener.onLocationChanged(Location(snapshot))
            },
            onError = { error ->
                Log.e(TAG, "Unable to start fused location session", error)
            }
        )
    }

    fun stopSession() {
        unifiedLocationClient.stopSession()
    }

    fun isGpsProviderEnabled(): Boolean {
        return unifiedLocationClient.isGpsProviderEnabled()
    }

    fun isLocationServicesEnabled(): Boolean {
        return unifiedLocationClient.isLocationServicesEnabled()
    }

    fun lastKnownGpsLocation(): Location? {
        val cached = lastDeliveredLocation
        if (cached != null) return Location(cached)
        return runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        }.getOrNull()
    }

    fun getLastLocation(
        onSuccess: (Location?) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        unifiedLocationClient.getLastLocation(
            onSuccess = { location ->
                if (location != null) {
                    lastDeliveredLocation = Location(location)
                }
                onSuccess(location)
            },
            onFailure = onFailure
        )
    }
}
