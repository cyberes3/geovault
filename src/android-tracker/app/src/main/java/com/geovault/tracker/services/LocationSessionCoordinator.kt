package com.geovault.tracker.services

import android.content.Context
import android.location.Location
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.positioning.collection.UnifiedLocationClient
import com.geovault.tracker.positioning.collection.UnifiedLocationSessionRequest
import com.google.android.gms.location.LocationRequest

interface LocationSessionGateway {
    fun startSession(request: LocationRequest): Boolean
    fun stopSession()
    fun isGpsProviderEnabled(): Boolean
    fun isLocationServicesEnabled(): Boolean
    fun getLastLocation(onSuccess: (Location?) -> Unit, onFailure: (Throwable) -> Unit)
}

class LocationSessionCoordinator(
    context: Context,
    private val onSessionError: (Throwable) -> Unit = {},
) : LocationSessionGateway {
    private companion object {
        private const val TAG = "LocationSessionCoordinator"
    }

    private val appContext = context.applicationContext
    private val unifiedLocationClient = UnifiedLocationClient(appContext)

    override fun startSession(
        request: LocationRequest
    ): Boolean {
        if (!unifiedLocationClient.hasLocationPermission()) return false
        return unifiedLocationClient.startSession(
            sessionRequest = UnifiedLocationSessionRequest(request),
            onError = { error ->
                GeoVaultCaptureLog.e(TAG, "Unable to start fused location session", error)
                onSessionError(error)
            }
        )
    }

    override fun stopSession() {
        unifiedLocationClient.stopSession()
    }

    override fun isGpsProviderEnabled(): Boolean {
        return unifiedLocationClient.isGpsProviderEnabled()
    }

    override fun isLocationServicesEnabled(): Boolean {
        return unifiedLocationClient.isLocationServicesEnabled()
    }

    override fun getLastLocation(
        onSuccess: (Location?) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        unifiedLocationClient.getLastLocation(
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }
}
