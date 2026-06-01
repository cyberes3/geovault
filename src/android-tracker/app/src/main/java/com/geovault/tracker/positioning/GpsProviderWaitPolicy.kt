package com.geovault.tracker.positioning

import com.geovault.tracker.positioning.config.GpsRuntimeState

object GpsProviderWaitPolicy {
    fun isWaitingForProviderState(gpsRuntimeState: GpsRuntimeState): Boolean {
        return gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER ||
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
    }
}
