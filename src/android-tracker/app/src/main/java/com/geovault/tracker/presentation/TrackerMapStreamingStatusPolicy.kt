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
        val desiredIds = normalizeIds(streamTargetIds)
        if (!snapshot.isRunning && desiredIds.isEmpty()) {
            return TrackerMapStreamingStatusUiModel()
        }

        val activeIds = normalizeIds(snapshot.activeTrackerIds)
        val activeCount = activeIds.size
        val desiredMatched = desiredIds.isNotEmpty() && activeIds == desiredIds

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
                status = if (desiredMatched) {
                    TrackerMapStreamingStatus.LIVE
                } else if (activeCount > 0) {
                    TrackerMapStreamingStatus.RECONNECTING
                } else {
                    TrackerMapStreamingStatus.CONNECTING
                },
                activeCount = activeCount,
            )
            TrackingLifecycleState.FAILED -> TrackerMapStreamingStatusUiModel(
                status = TrackerMapStreamingStatus.FAILED,
                activeCount = activeCount,
                failureReason = snapshot.failureReason,
            )
            TrackingLifecycleState.STOPPED -> {
                TrackerMapStreamingStatusUiModel()
            }
            else -> TrackerMapStreamingStatusUiModel()
        }
    }

    private fun normalizeIds(ids: Set<String>): Set<String> {
        return ids.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
    }
}
