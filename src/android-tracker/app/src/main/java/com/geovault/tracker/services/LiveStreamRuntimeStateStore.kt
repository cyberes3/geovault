package com.geovault.tracker.services

import com.geovault.tracker.location.TrackingLifecycleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveStreamRuntimeSnapshot(
    val isRunning: Boolean = false,
    val lifecycleState: TrackingLifecycleState = TrackingLifecycleState.STOPPED,
    val activeTrackerIds: Set<String> = emptySet(),
    val failureReason: String? = null,
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
