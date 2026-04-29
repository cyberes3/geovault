package com.geovault.tracker.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.geovault.tracker.TrackingLocationUpdateReceiver
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

    @SuppressLint("MissingPermission")
    fun startSession(
        sessionRequest: UnifiedLocationSessionRequest,
        onError: (Throwable) -> Unit
    ): Boolean {
        stopSession()
        return try {
            fusedClient.requestLocationUpdates(
                sessionRequest.request,
                sessionPendingIntent
            )
            true
        } catch (t: Throwable) {
            onError(t)
            false
        }
    }

    fun stopSession() {
        fusedClient.removeLocationUpdates(sessionPendingIntent)
    }

    @SuppressLint("MissingPermission")
    fun getLastLocation(
        onSuccess: (Location?) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        fusedClient.lastLocation
            .addOnSuccessListener { location -> onSuccess(location) }
            .addOnFailureListener { error -> onFailure(error) }
    }
}
