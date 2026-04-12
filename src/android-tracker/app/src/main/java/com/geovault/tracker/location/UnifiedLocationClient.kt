package com.geovault.tracker.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices

data class UnifiedLocationSessionRequest(
    val request: LocationRequest
)

class UnifiedLocationClient(context: Context) {
    private val appContext = context.applicationContext
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var sessionCallback: LocationCallback? = null

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun isGpsProviderEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun startSession(
        sessionRequest: UnifiedLocationSessionRequest,
        onLocation: (Location) -> Unit,
        onError: (Throwable) -> Unit
    ): Boolean {
        stopSession()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach(onLocation)
            }
        }
        return try {
            fusedClient.requestLocationUpdates(
                sessionRequest.request,
                callback,
                Looper.getMainLooper()
            )
            sessionCallback = callback
            true
        } catch (t: Throwable) {
            onError(t)
            false
        }
    }

    fun stopSession() {
        val callback = sessionCallback ?: return
        fusedClient.removeLocationUpdates(callback)
        sessionCallback = null
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
