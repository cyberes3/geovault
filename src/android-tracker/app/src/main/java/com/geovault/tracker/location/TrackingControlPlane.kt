package com.geovault.tracker.location

internal enum class TrackingControlEvent {
    StartRequested,
    StartSucceeded,
    StartFailed,
    PauseRequested,
    ResumeRequested,
    StopRequested,
    StopCompleted,
    FatalFailure
}

internal data class TrackingControlState(
    val lifecycleState: TrackingLifecycleState = TrackingLifecycleState.STOPPED,
    val failureReason: String? = null
)
