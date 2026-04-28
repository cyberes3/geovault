package com.geovault.common.maps.location

import android.location.Location
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Polls [MapLocationRendererPlugin.getLastLocation] until non-null or [timeoutMs] elapses.
 * Used when navigation framing wants user + target in view after a cold permission grant.
 */
suspend fun geoVaultMapPollLastKnownLocation(
    userLocation: MapLocationRendererPlugin,
    pollIntervalMs: Long = 100L,
    timeoutMs: Long = 15_000L,
): Location? {
    var waited = 0L
    while (coroutineContext.isActive && waited < timeoutMs) {
        userLocation.getLastLocation()?.let { return it }
        delay(pollIntervalMs)
        waited += pollIntervalMs
    }
    return userLocation.getLastLocation()
}
