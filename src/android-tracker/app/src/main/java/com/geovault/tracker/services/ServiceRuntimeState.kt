package com.geovault.tracker.services

import com.geovault.tracker.location.TrackingLifecycleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrackingRuntimeSnapshot(
    val isRunning: Boolean = false,
    val lifecycleState: TrackingLifecycleState = TrackingLifecycleState.STOPPED,
    val failureReason: String? = null,
    val gpsProviderEnabled: Boolean = true,
    val sessionStartTimeMs: Long = 0L,
    val pointsSentThisSession: Int = 0,
    val lastPointSentAtMs: Long = 0L,
    val sessionTotalDistanceMeters: Float = 0f,
    val lastAccuracyMeters: Float? = null,
    val lastTrackedLatitude: Double? = null,
    val lastTrackedLongitude: Double? = null,
    val lastTrackedTimestampMs: Long = 0L,
    val lastTrackedPropsJson: String? = null
)

object TrackingRuntimeStateStore {
    private val _state = MutableStateFlow(TrackingRuntimeSnapshot())
    val state: StateFlow<TrackingRuntimeSnapshot> = _state.asStateFlow()
    private val lock = Any()

    fun update(transform: (TrackingRuntimeSnapshot) -> TrackingRuntimeSnapshot) {
        synchronized(lock) {
            _state.value = transform(_state.value)
        }
    }
}

data class LiveStreamRuntimeSnapshot(
    val isRunning: Boolean = false,
    val activeTrackerIds: Set<String> = emptySet()
)

object LiveStreamRuntimeStateStore {
    private val _state = MutableStateFlow(LiveStreamRuntimeSnapshot())
    val state: StateFlow<LiveStreamRuntimeSnapshot> = _state.asStateFlow()
    private val lock = Any()

    fun update(transform: (LiveStreamRuntimeSnapshot) -> LiveStreamRuntimeSnapshot) {
        synchronized(lock) {
            _state.value = transform(_state.value)
        }
    }
}
