package com.geovault.common.maps.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices

/**
 * Continuous location delivery via Play Services fused client, with LocationManager fallback
 * when fused registration fails.
 */
class PlayServicesContinuousLocationProvider(
    context: Context,
) : FusedLocationProviderPort {
    private val appContext = context.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val activeFallbackListeners = mutableMapOf<LocationCallback, LocationListener>()

    @SuppressLint("MissingPermission")
    override fun requestUpdates(
        request: LocationRequest,
        callback: LocationCallback,
        looper: Looper,
    ) {
        try {
            fusedClient.requestLocationUpdates(request, callback, looper)
        } catch (_: Throwable) {
            startLocationManagerFallback(request, callback, looper)
        }
    }

    @SuppressLint("MissingPermission")
    override fun removeUpdates(callback: LocationCallback) {
        runCatching { fusedClient.removeLocationUpdates(callback) }
        val fallback = activeFallbackListeners.remove(callback) ?: return
        runCatching { locationManager.removeUpdates(fallback) }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationManagerFallback(
        request: LocationRequest,
        callback: LocationCallback,
        looper: Looper,
    ) {
        val provider = pickBestProvider() ?: return
        val handler = Handler(looper)
        val listener = LocationListener { location ->
            val valid = LocationUpdates.validLocationOrNull(location) ?: return@LocationListener
            handler.post {
                callback.onLocationResult(LocationResult.create(listOf(valid)))
            }
        }
        activeFallbackListeners[callback] = listener
        locationManager.requestLocationUpdates(
            provider,
            request.intervalMillis.coerceAtLeast(MIN_INTERVAL_MS),
            0f,
            listener,
            looper,
        )
    }

    private fun pickBestProvider(): String? {
        return when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) ->
                LocationManager.PASSIVE_PROVIDER
            else -> null
        }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 500L
    }
}
