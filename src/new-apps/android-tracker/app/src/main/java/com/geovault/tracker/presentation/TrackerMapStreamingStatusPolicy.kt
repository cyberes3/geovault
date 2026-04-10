package com.geovault.tracker.presentation

import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.services.LiveStreamRuntimeSnapshot

enum class TrackerMapStreamingStatus {
    INACTIVE,
    CONNECTING,
    LIVE,
    RECONNECTING,
    FAILED,
}

data class TrackerMapStreamingStatusUiModel(
    val status: TrackerMapStreamingStatus = TrackerMapStreamingStatus.INACTIVE,
    val activeCount: Int = 0,
    val failureReason: String? = null,
)

object TrackerMapStreamingStatusPolicy {
    fun resolve(
        snapshot: LiveStreamRuntimeSnapshot,
        streamTargetIds: Set<String>,
    ): TrackerMapStreamingStatusUiModel {
        if (!snapshot.isRunning && streamTargetIds.isEmpty()) {
            return TrackerMapStreamingStatusUiModel()
        }

        val activeCount = snapshot.activeTrackerIds.size

        return when (snapshot.lifecycleState) {
            TrackingLifecycleState.STARTING -> TrackerMapStreamingStatusUiModel(
                status = if (activeCount > 0) {
                    TrackerMapStreamingStatus.RECONNECTING
                } else {
                    TrackerMapStreamingStatus.CONNECTING
                },
                activeCount = activeCount,
            )
            TrackingLifecycleState.RUNNING -> TrackerMapStreamingStatusUiModel(
                status = TrackerMapStreamingStatus.LIVE,
                activeCount = activeCount,
            )
            TrackingLifecycleState.FAILED -> TrackerMapStreamingStatusUiModel(
                status = TrackerMapStreamingStatus.FAILED,
                activeCount = activeCount,
                failureReason = snapshot.failureReason,
            )
            TrackingLifecycleState.STOPPED -> {
                if (streamTargetIds.isNotEmpty() && snapshot.isRunning) {
                    TrackerMapStreamingStatusUiModel(
                        status = TrackerMapStreamingStatus.CONNECTING,
                        activeCount = 0,
                    )
                } else {
                    TrackerMapStreamingStatusUiModel()
                }
            }
            else -> TrackerMapStreamingStatusUiModel()
        }
    }
}
