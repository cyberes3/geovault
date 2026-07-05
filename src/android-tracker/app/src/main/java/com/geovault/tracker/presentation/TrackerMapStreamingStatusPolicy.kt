package com.geovault.tracker.presentation

import com.geovault.tracker.streaming.ConnectionPhase
import com.geovault.tracker.streaming.LiveStreamSubscriptionState

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
 * STREAM-STATE-MACHINE: status mapping is driven by [ConnectionPhase] directly so consumers see
 * distinct UI for "starting fresh" vs "reconnecting after a drop", and so a permanent failure is
 * not silently lumped in with a transient blip.
 */
object TrackerMapStreamingStatusPolicy {
    fun resolve(
        snapshot: LiveStreamSubscriptionState,
        streamTargetIds: Set<String>,
    ): TrackerMapStreamingStatusUiModel {
        val desiredIds = normalizeIds(streamTargetIds)
        if (!snapshot.wantsSubscription && desiredIds.isEmpty()) {
            return TrackerMapStreamingStatusUiModel()
        }

        val activeIds = normalizeIds(snapshot.activeTargets)
        val activeCount = activeIds.size
        val desiredMatched = desiredIds.isNotEmpty() && activeIds == desiredIds

        return when (snapshot.connection) {
            ConnectionPhase.STARTING -> TrackerMapStreamingStatusUiModel(
                status = if (activeCount > 0) {
                    TrackerMapStreamingStatus.RECONNECTING
                } else {
                    TrackerMapStreamingStatus.CONNECTING
                },
                activeCount = activeCount,
            )
            ConnectionPhase.RECONNECTING -> TrackerMapStreamingStatusUiModel(
                status = TrackerMapStreamingStatus.RECONNECTING,
                activeCount = activeCount,
            )
            ConnectionPhase.RUNNING -> TrackerMapStreamingStatusUiModel(
                status = if (desiredMatched) {
                    TrackerMapStreamingStatus.LIVE
                } else if (activeCount > 0) {
                    TrackerMapStreamingStatus.RECONNECTING
                } else {
                    TrackerMapStreamingStatus.CONNECTING
                },
                activeCount = activeCount,
            )
            ConnectionPhase.FAILED_TRANSIENT,
            ConnectionPhase.FAILED_PERMANENT -> TrackerMapStreamingStatusUiModel(
                status = TrackerMapStreamingStatus.FAILED,
                activeCount = activeCount,
                failureReason = snapshot.failureReason,
            )
            ConnectionPhase.IDLE -> TrackerMapStreamingStatusUiModel()
        }
    }

    private fun normalizeIds(ids: Set<String>): Set<String> {
        return ids.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
    }
}
