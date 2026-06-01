package com.geovault.tracker.positioning.collection

import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.geovault.tracker.location.TrackingLocationAvailabilityPolicy
import com.geovault.tracker.TrackingLocationUpdateReceiver
import com.geovault.tracker.location.TrackingPermissionGate
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices

data class UnifiedLocationSessionRequest(
    val request: LocationRequest
)

class UnifiedLocationClient(context: Context) {
    private val appContext = context.applicationContext
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sessionPendingIntent = TrackingLocationUpdateReceiver.pendingIntent(appContext)

    fun hasLocationPermission(): Boolean {
        return TrackingLocationAvailabilityPolicy.canRequestTrackingLocationUpdates(
            hasFineLocationPermission = TrackingPermissionGate.hasLocationPermission(appContext)
        )
    }

    fun isGpsProviderEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    fun isLocationServicesEnabled(): Boolean = TrackingPermissionGate.isLocationServicesEnabled(appContext)

    fun startSession(
        sessionRequest: UnifiedLocationSessionRequest,
        onError: (Throwable) -> Unit
    ): Boolean {
        if (!hasLocationPermission()) {
            onError(SecurityException("Missing location permission for fused session"))
            return false
        }
        return try {
            fusedClient.requestLocationUpdates(
                sessionRequest.request,
                sessionPendingIntent
            ).addOnFailureListener { error -> onError(error) }
            true
        } catch (t: Throwable) {
            onError(t)
            false
        }
    }

    fun stopSession() {
        fusedClient.removeLocationUpdates(sessionPendingIntent)
    }

    fun getLastLocation(
        onSuccess: (Location?) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        if (!hasLocationPermission()) {
            onFailure(SecurityException("Missing location permission for last location"))
            return
        }
        fusedClient.lastLocation
            .addOnSuccessListener { location -> onSuccess(location) }
            .addOnFailureListener { error -> onFailure(error) }
    }
}
