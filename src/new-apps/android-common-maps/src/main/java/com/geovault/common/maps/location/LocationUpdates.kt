package com.geovault.common.maps.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.maplibre.android.geometry.LatLng

object LocationUpdates {
    private const val DEFAULT_MIN_DISTANCE_METERS = 0f

    @SuppressLint("MissingPermission")
    fun getCurrentLocation(context: Context, callback: (LatLng?) -> Unit) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
        val mainHandler = Handler(Looper.getMainLooper())
        fun deliver(result: LatLng?) {
            mainHandler.post { callback(result) }
        }
        fusedClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    deliver(LatLng(location.latitude, location.longitude))
                } else {
                    deliver(getBestLastKnownLatLng(manager))
                }
            }
            .addOnFailureListener {
                val provider = pickBestProvider(manager)
                if (provider == null) {
                    deliver(getBestLastKnownLatLng(manager))
                    return@addOnFailureListener
                }
                runCatching {
                    manager.getCurrentLocation(
                        provider,
                        null,
                        ContextCompat.getMainExecutor(appContext),
                    ) { location ->
                        if (location != null) {
                            deliver(LatLng(location.latitude, location.longitude))
                        } else {
                            deliver(getBestLastKnownLatLng(manager))
                        }
                    }
                }.onFailure {
                    deliver(getBestLastKnownLatLng(manager))
                }
            }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(
        context: Context,
        intervalMs: Long,
        callback: (LatLng, Location?) -> Unit,
    ): LocationUpdatesSession {
        val appContext = context.applicationContext
        val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            intervalMs.coerceAtLeast(500L)
        )
            .setMinUpdateDistanceMeters(DEFAULT_MIN_DISTANCE_METERS)
            .setMinUpdateIntervalMillis((intervalMs / 2L).coerceAtLeast(250L))
            .build()
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    callback(LatLng(location.latitude, location.longitude), location)
                }
            }
        }
        return try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            object : LocationUpdatesSession {
                override fun stop() {
                    fusedClient.removeLocationUpdates(locationCallback)
                }
            }
        } catch (_: Throwable) {
            startLocationUpdatesWithLocationManager(
                context = appContext,
                intervalMs = intervalMs,
                callback = callback
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdatesWithLocationManager(
        context: Context,
        intervalMs: Long,
        callback: (LatLng, Location?) -> Unit
    ): LocationUpdatesSession {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = pickBestProvider(manager)
        if (provider == null) {
            return object : LocationUpdatesSession {
                override fun stop() = Unit
            }
        }
        val mainHandler = Handler(Looper.getMainLooper())
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val latLng = LatLng(location.latitude, location.longitude)
                mainHandler.post { callback(latLng, location) }
            }
        }
        manager.requestLocationUpdates(
            provider,
            intervalMs.coerceAtLeast(500L),
            DEFAULT_MIN_DISTANCE_METERS,
            locationListener,
            Looper.getMainLooper(),
        )
        return object : LocationUpdatesSession {
            override fun stop() {
                manager.removeUpdates(locationListener)
            }
        }
    }

    private fun pickBestProvider(manager: LocationManager): String? {
        return when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) -> LocationManager.PASSIVE_PROVIDER
            else -> null
        }
    }

    private fun getBestLastKnownLatLng(manager: LocationManager): LatLng? {
        val candidateProviders = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        val last = candidateProviders
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?: return null
        return LatLng(last.latitude, last.longitude)
    }

    interface LocationUpdatesSession {
        fun stop()
    }
}
