package com.geovault.tracker.presentation

import com.geovault.tracker.services.LiveStreamRuntimeSnapshot
import com.geovault.tracker.services.StreamingHealth

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

/**
 * STREAM-STATE-MACHINE: status mapping is now driven by [StreamingHealth] directly so consumers
 * see distinct UI for "starting fresh" vs "reconnecting after a drop", and so a permanent
 * failure is not silently lumped in with a transient blip.
 */
object TrackerMapStreamingStatusPolicy {
    fun resolve(
        snapshot: LiveStreamRuntimeSnapshot,
        streamTargetIds: Set<String>,
    ): TrackerMapStreamingStatusUiModel {
        val desiredIds = normalizeIds(streamTargetIds)
        if (!snapshot.wantsSubscription && desiredIds.isEmpty()) {
            return TrackerMapStreamingStatusUiModel()
        }

        val activeIds = normalizeIds(snapshot.activeTrackerIds)
        val activeCount = activeIds.size
        val desiredMatched = desiredIds.isNotEmpty() && activeIds == desiredIds

        return when (snapshot.health) {
            StreamingHealth.Starting -> TrackerMapStreamingStatusUiModel(
                status = if (activeCount > 0) {
                    TrackerMapStreamingStatus.RECONNECTING
                } else {
                    TrackerMapStreamingStatus.CONNECTING
                },
                activeCount = activeCount,
            )
            StreamingHealth.Reconnecting -> TrackerMapStreamingStatusUiModel(
                status = TrackerMapStreamingStatus.RECONNECTING,
                activeCount = activeCount,
            )
            StreamingHealth.Running -> TrackerMapStreamingStatusUiModel(
                status = if (desiredMatched) {
                    TrackerMapStreamingStatus.LIVE
                } else if (activeCount > 0) {
                    TrackerMapStreamingStatus.RECONNECTING
                } else {
                    TrackerMapStreamingStatus.CONNECTING
                },
                activeCount = activeCount,
            )
            StreamingHealth.FailedTransient,
            StreamingHealth.FailedPermanent -> TrackerMapStreamingStatusUiModel(
                status = TrackerMapStreamingStatus.FAILED,
                activeCount = activeCount,
                failureReason = snapshot.failureReason,
            )
            StreamingHealth.Stopped -> TrackerMapStreamingStatusUiModel()
        }
    }

    private fun normalizeIds(ids: Set<String>): Set<String> {
        return ids.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
    }
}
