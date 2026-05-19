package com.geovault.tracker.services

import android.content.Context
import android.location.Location
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.location.UnifiedLocationClient
import com.geovault.tracker.location.UnifiedLocationSessionRequest
import com.google.android.gms.location.LocationRequest

class LocationSessionCoordinator(
    context: Context,
    private val onSessionError: (Throwable) -> Unit = {},
) {
    private companion object {
        private const val TAG = "LocationSessionCoordinator"
    }

    private val appContext = context.applicationContext
    private val unifiedLocationClient = UnifiedLocationClient(appContext)

    fun startSession(
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

    fun stopSession() {
        unifiedLocationClient.stopSession()
    }

    fun isGpsProviderEnabled(): Boolean {
        return unifiedLocationClient.isGpsProviderEnabled()
    }

    fun isLocationServicesEnabled(): Boolean {
        return unifiedLocationClient.isLocationServicesEnabled()
    }

    fun getLastLocation(
        onSuccess: (Location?) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        unifiedLocationClient.getLastLocation(
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }
}
