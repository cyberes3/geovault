package com.geovault.common.maps.location

import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest

/**
 * Testable boundary over continuous fused (or fused-equivalent) location delivery.
 *
 * Production code uses [PlayServicesContinuousLocationProvider]. Unit tests supply fakes so the
 * [GeoVaultMapGpsLocationEngine] coordinator can be verified without Play Services.
 */
interface FusedLocationProviderPort {
    fun requestUpdates(
        request: LocationRequest,
        callback: LocationCallback,
        looper: Looper,
    )

    fun removeUpdates(callback: LocationCallback)
}
