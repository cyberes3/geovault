package com.geovault.tracker.positioning

import com.geovault.tracker.positioning.config.GpsRuntimeState

object LocationRequestController {
    fun expectsActiveFixDelivery(isTracking: Boolean, gpsRuntimeState: GpsRuntimeState): Boolean {
        return isTracking &&
            gpsRuntimeState != GpsRuntimeState.INACTIVE &&
            gpsRuntimeState != GpsRuntimeState.PAUSED_FOR_MOTION &&
            gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER &&
            gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
    }
}
