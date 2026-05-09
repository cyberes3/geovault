package com.geovault.common.maps.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.geovault.common.maps.core.latLngOrNull
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.geometry.LatLng
import kotlin.coroutines.resume

object LocationUpdates {
    private const val DEFAULT_MIN_DISTANCE_METERS = 0f

    /**
     * Coroutine-friendly wrapper around [getCurrentLocation] with a hard timeout. Returns the
     * resolved [LatLng] (cached fix or fresh single-shot) or `null` if no fix arrives within
     * [timeoutMs]. Caller must hold location permission.
     *
     * Useful inside `LaunchedEffect`s that need a "best-effort current fix" without blocking
     * the composition forever when the GPS provider is unresponsive.
     */
    suspend fun getCurrentLatLngOnce(context: Context, timeoutMs: Long = 4000L): LatLng? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<LatLng?> { cont ->
                getCurrentLocationFix(context) { location ->
                    if (cont.isActive) cont.resume(location?.toValidLatLngOrNull())
                }
            }
        }

    /**
     * Resolves a fresh current fix, never a cached / last-known location.
     *
     * Use this for explicit GPS-focus actions where jumping to an old stored location is worse
     * than waiting briefly for a real lock.
     */
    suspend fun getFreshCurrentLatLngOnce(context: Context, timeoutMs: Long = 4000L): LatLng? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<LatLng?> { cont ->
                getFreshCurrentLocationFix(context) { location ->
                    if (cont.isActive) cont.resume(location?.toValidLatLngOrNull())
                }
            }
        }

    @SuppressLint("MissingPermission")
    fun getFreshCurrentLocation(context: Context, callback: (LatLng?) -> Unit) {
        getFreshCurrentLocationFix(context) { location ->
            callback(location?.toValidLatLngOrNull())
        }
    }

    @SuppressLint("MissingPermission")
    fun getFreshCurrentLocationFix(context: Context, callback: (Location?) -> Unit) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
        val mainHandler = Handler(Looper.getMainLooper())
        val cancellation = CancellationTokenSource()
        fun deliver(result: Location?) {
            mainHandler.post { callback(result) }
        }
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0L)
            .build()
        fusedClient.getCurrentLocation(request, cancellation.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    deliver(validLocationOrNull(location))
                } else {
                    requestFreshCurrentLocationWithLocationManager(
                        appContext = appContext,
                        manager = manager,
                        deliver = ::deliver,
                    )
                }
            }
            .addOnFailureListener {
                requestFreshCurrentLocationWithLocationManager(
                    appContext = appContext,
                    manager = manager,
                    deliver = ::deliver,
                )
            }
    }

    @SuppressLint("MissingPermission")
    fun getCurrentLocation(context: Context, callback: (LatLng?) -> Unit) {
        getCurrentLocationFix(context) { location ->
            callback(location?.toValidLatLngOrNull())
        }
    }

    @SuppressLint("MissingPermission")
    fun getCurrentLocationFix(context: Context, callback: (Location?) -> Unit) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
        val mainHandler = Handler(Looper.getMainLooper())
        fun deliver(result: Location?) {
            mainHandler.post { callback(result) }
        }
        fusedClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    deliver(validLocationOrNull(location))
                } else {
                    requestCurrentLocationWithLocationManager(
                        appContext = appContext,
                        manager = manager,
                        deliver = ::deliver,
                    )
                }
            }
            .addOnFailureListener {
                requestCurrentLocationWithLocationManager(
                    appContext = appContext,
                    manager = manager,
                    deliver = ::deliver,
                )
            }
    }

    @SuppressLint("MissingPermission")
    private fun requestCurrentLocationWithLocationManager(
        appContext: Context,
        manager: LocationManager,
        deliver: (Location?) -> Unit,
    ) {
        val provider = pickBestProvider(manager)
        if (provider == null) {
            deliver(getBestLastKnownLocation(manager))
            return
        }
        runCatching {
            manager.getCurrentLocation(
                provider,
                null,
                ContextCompat.getMainExecutor(appContext),
            ) { location ->
                if (location != null) {
                    deliver(validLocationOrNull(location))
                } else {
                    deliver(getBestLastKnownLocation(manager))
                }
            }
        }.onFailure {
            deliver(getBestLastKnownLocation(manager))
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshCurrentLocationWithLocationManager(
        appContext: Context,
        manager: LocationManager,
        deliver: (Location?) -> Unit,
    ) {
        val provider = pickBestProvider(manager)
        if (provider == null) {
            deliver(null)
            return
        }
        runCatching {
            manager.getCurrentLocation(
                provider,
                null,
                ContextCompat.getMainExecutor(appContext),
            ) { location ->
                deliver(validLocationOrNull(location))
            }
        }.onFailure {
            deliver(null)
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
                    val latLng = latLngOrNull(location.latitude, location.longitude) ?: return@forEach
                    callback(latLng, location)
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
                val latLng = latLngOrNull(location.latitude, location.longitude) ?: return
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

    private fun getBestLastKnownLocation(manager: LocationManager): Location? {
        val candidateProviders = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        return candidateProviders
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .filter { it.toValidLatLngOrNull() != null }
            .maxByOrNull { it.time }
    }

    internal fun validLocationOrNull(location: Location?): Location? {
        return location?.takeIf { it.toValidLatLngOrNull() != null }
    }

    internal fun Location.toValidLatLngOrNull(): LatLng? {
        return latLngOrNull(latitude, longitude)
    }

    interface LocationUpdatesSession {
        fun stop()
    }
}
