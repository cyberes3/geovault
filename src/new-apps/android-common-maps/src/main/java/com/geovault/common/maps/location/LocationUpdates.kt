package com.geovault.common.maps.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.maplibre.android.geometry.LatLng

object LocationUpdates {
    private const val DEFAULT_MIN_DISTANCE_METERS = 0f

    fun getCurrentLocation(context: Context, callback: (LatLng?) -> Unit) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val mainHandler = Handler(Looper.getMainLooper())
        fun deliver(result: LatLng?) {
            mainHandler.post { callback(result) }
        }
        val provider = pickBestProvider(manager)
        if (provider == null) {
            deliver(getBestLastKnownLatLng(manager))
            return
        }
        runCatching {
            manager.getCurrentLocation(
                provider,
                null,
                ContextCompat.getMainExecutor(context),
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

    fun startLocationUpdates(
        context: Context,
        intervalMs: Long,
        callback: (LatLng, Location?) -> Unit,
    ): LocationUpdatesSession {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val mainHandler = Handler(Looper.getMainLooper())
        val provider = pickBestProvider(manager)
        if (provider == null) {
            return object : LocationUpdatesSession {
                override fun stop() = Unit
            }
        }
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
