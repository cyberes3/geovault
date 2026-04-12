package com.geovault.common.location

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.maplibre.android.geometry.LatLng

/**
 * One-shot and continuous location entry points so apps don't wire
 * FusedLocationProviderClient, permission checks, or main-thread callbacks themselves.
 * Caller must ensure ACCESS_FINE_LOCATION is granted before calling.
 */
object LocationUpdates {

    /**
     * One-shot: uses getCurrentLocation(PRIORITY_HIGH_ACCURACY) with fallback to lastLocation.
     * Invokes [callback] on the main thread with LatLng or null.
     */
    fun getCurrentLocation(context: Context, callback: (LatLng?) -> Unit) {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val mainHandler = Handler(Looper.getMainLooper())
        fun deliver(result: LatLng?) {
            mainHandler.post { callback(result) }
        }
        val tokenSource = CancellationTokenSource()
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    deliver(LatLng(location.latitude, location.longitude))
                } else {
                    client.lastLocation
                        .addOnSuccessListener { last: Location? ->
                            deliver(last?.let { LatLng(it.latitude, it.longitude) })
                        }
                        .addOnFailureListener { deliver(null) }
                }
            }
            .addOnFailureListener {
                client.lastLocation
                    .addOnSuccessListener { last: Location? ->
                        deliver(last?.let { LatLng(it.latitude, it.longitude) })
                    }
                    .addOnFailureListener { deliver(null) }
            }
    }

    /**
     * Continuous: builds LocationRequest with [intervalMs], registers LocationCallback,
     * invokes [callback] on the main thread with LatLng and raw Location (for accuracy, bearing).
     * Returns a session with [LocationUpdatesSession.stop] to stop updates.
     */
    fun startLocationUpdates(
        context: Context,
        intervalMs: Long,
        callback: (LatLng, Location?) -> Unit
    ): LocationUpdatesSession {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val mainHandler = Handler(Looper.getMainLooper())
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs.coerceAtMost(500L))
            .setMaxUpdateDelayMillis(intervalMs * 2)
            .build()
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val latLng = LatLng(location.latitude, location.longitude)
                mainHandler.post { callback(latLng, location) }
            }
        }
        client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        return object : LocationUpdatesSession {
            override fun stop() {
                client.removeLocationUpdates(locationCallback)
            }
        }
    }

    interface LocationUpdatesSession {
        fun stop()
    }
}
