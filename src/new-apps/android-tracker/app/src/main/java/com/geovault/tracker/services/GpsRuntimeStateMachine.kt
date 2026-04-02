package com.geovault.tracker.services

enum class GpsRuntimeState {
    INACTIVE,
    RUNNING,
    LOCKING,
    WAITING_FOR_PROVIDER,
    PAUSED_FOR_MOTION,
    FALLBACK_PENDING
}

enum class GpsRuntimeEvent {
    TRACKING_STARTED,
    TRACKING_STOPPED,
    FIX_ACCEPTED,
    FIX_REJECTED,
    PROVIDER_DISABLED,
    PROVIDER_ENABLED,
    FALLBACK_TIMER_ARMED,
    FALLBACK_EMITTED,
    PAUSE_FOR_MOTION,
    RESUME_FROM_MOTION
}

object GpsRuntimeStateMachine {
    fun transition(current: GpsRuntimeState, event: GpsRuntimeEvent): GpsRuntimeState {
        return when (event) {
            GpsRuntimeEvent.TRACKING_STARTED -> GpsRuntimeState.LOCKING
            GpsRuntimeEvent.TRACKING_STOPPED -> GpsRuntimeState.INACTIVE
            GpsRuntimeEvent.FIX_ACCEPTED -> GpsRuntimeState.RUNNING
            GpsRuntimeEvent.FIX_REJECTED -> {
                if (current == GpsRuntimeState.WAITING_FOR_PROVIDER) current else GpsRuntimeState.LOCKING
            }
            GpsRuntimeEvent.PROVIDER_DISABLED -> GpsRuntimeState.WAITING_FOR_PROVIDER
            GpsRuntimeEvent.PROVIDER_ENABLED -> GpsRuntimeState.LOCKING
            GpsRuntimeEvent.FALLBACK_TIMER_ARMED -> GpsRuntimeState.FALLBACK_PENDING
            GpsRuntimeEvent.FALLBACK_EMITTED -> GpsRuntimeState.RUNNING
            GpsRuntimeEvent.PAUSE_FOR_MOTION -> GpsRuntimeState.PAUSED_FOR_MOTION
            GpsRuntimeEvent.RESUME_FROM_MOTION -> GpsRuntimeState.LOCKING
        }
    }
}
