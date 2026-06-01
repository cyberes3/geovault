package com.geovault.tracker.positioning.config

enum class GpsRuntimeState {
    INACTIVE,
    RUNNING,
    LOCKING,
    WAITING_FOR_PROVIDER,
    WAITING_FOR_PROVIDER_PAUSED,
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
    FAST_LOCK_STARTED,
    FAST_LOCK_TIMEOUT,
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
                if (
                    current == GpsRuntimeState.WAITING_FOR_PROVIDER ||
                    current == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
                ) {
                    current
                } else {
                    GpsRuntimeState.LOCKING
                }
            }
            GpsRuntimeEvent.PROVIDER_DISABLED -> {
                when (current) {
                    GpsRuntimeState.PAUSED_FOR_MOTION,
                    GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED -> GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
                    else -> GpsRuntimeState.WAITING_FOR_PROVIDER
                }
            }
            GpsRuntimeEvent.PROVIDER_ENABLED -> {
                when (current) {
                    GpsRuntimeState.WAITING_FOR_PROVIDER -> GpsRuntimeState.LOCKING
                    GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED -> GpsRuntimeState.PAUSED_FOR_MOTION
                    else -> current
                }
            }
            GpsRuntimeEvent.FAST_LOCK_STARTED -> GpsRuntimeState.LOCKING
            GpsRuntimeEvent.FAST_LOCK_TIMEOUT -> GpsRuntimeState.LOCKING
            GpsRuntimeEvent.FALLBACK_TIMER_ARMED -> GpsRuntimeState.FALLBACK_PENDING
            GpsRuntimeEvent.FALLBACK_EMITTED -> GpsRuntimeState.RUNNING
            GpsRuntimeEvent.PAUSE_FOR_MOTION -> {
                when (current) {
                    GpsRuntimeState.WAITING_FOR_PROVIDER,
                    GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED -> GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
                    else -> GpsRuntimeState.PAUSED_FOR_MOTION
                }
            }
            GpsRuntimeEvent.RESUME_FROM_MOTION -> {
                if (current == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED) {
                    GpsRuntimeState.WAITING_FOR_PROVIDER
                } else {
                    GpsRuntimeState.LOCKING
                }
            }
        }
    }
}
