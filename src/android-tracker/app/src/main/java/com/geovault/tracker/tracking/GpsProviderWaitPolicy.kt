package com.geovault.tracker.tracking

import com.geovault.tracker.services.GpsRuntimeState

object GpsProviderWaitPolicy {
    fun isWaitingForProviderState(gpsRuntimeState: GpsRuntimeState): Boolean {
        return gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER ||
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
    }
}
