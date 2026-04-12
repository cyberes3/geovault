package com.geovault.tracker.location

enum class TrackingControlEvent {
    StartRequested,
    StartSucceeded,
    StartFailed,
    PauseRequested,
    ResumeRequested,
    StopRequested,
    StopCompleted,
    FatalFailure
}

data class TrackingControlState(
    val lifecycleState: TrackingLifecycleState = TrackingLifecycleState.STOPPED,
    val failureReason: String? = null
)

object TrackingControlPlane {
    fun transition(
        current: TrackingControlState,
        event: TrackingControlEvent,
        failureReason: String? = null
    ): TrackingControlState {
        return when (event) {
            TrackingControlEvent.StartRequested -> TrackingControlState(TrackingLifecycleState.STARTING, null)
            TrackingControlEvent.StartSucceeded -> TrackingControlState(TrackingLifecycleState.RUNNING, null)
            TrackingControlEvent.StartFailed -> TrackingControlState(TrackingLifecycleState.FAILED, failureReason)
            TrackingControlEvent.PauseRequested -> TrackingControlState(TrackingLifecycleState.PAUSED, null)
            TrackingControlEvent.ResumeRequested -> TrackingControlState(TrackingLifecycleState.RUNNING, null)
            TrackingControlEvent.StopRequested -> TrackingControlState(TrackingLifecycleState.STOPPING, current.failureReason)
            TrackingControlEvent.StopCompleted -> TrackingControlState(TrackingLifecycleState.STOPPED, null)
            TrackingControlEvent.FatalFailure ->
                TrackingControlState(TrackingLifecycleState.FAILED, failureReason ?: current.failureReason)
        }
    }
}
