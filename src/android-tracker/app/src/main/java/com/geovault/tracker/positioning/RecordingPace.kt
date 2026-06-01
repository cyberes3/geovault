package com.geovault.tracker.positioning

import com.geovault.tracker.positioning.config.GpsRuntimeState

enum class RecordingPace {
    Moving,
    Stationary,
    ;

    companion object {
        fun from(
            gpsRuntimeState: GpsRuntimeState,
            stationaryRegionActive: Boolean = false,
        ): RecordingPace {
            if (stationaryRegionActive) return Stationary
            return when (gpsRuntimeState) {
                GpsRuntimeState.PAUSED_FOR_MOTION,
                GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED,
                -> Stationary
                GpsRuntimeState.INACTIVE -> Stationary
                GpsRuntimeState.RUNNING,
                GpsRuntimeState.LOCKING,
                GpsRuntimeState.FALLBACK_PENDING,
                GpsRuntimeState.WAITING_FOR_PROVIDER,
                -> Moving
            }
        }
    }
}
