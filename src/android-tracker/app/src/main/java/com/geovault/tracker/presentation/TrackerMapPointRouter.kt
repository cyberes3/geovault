package com.geovault.tracker.presentation

import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource

data class TrackerMapPointRoute(
    val accepted: Boolean,
    val normalizedTrackerId: String = "",
    val updateRemoteLastPoint: Boolean = false,
    val appendSingleTrail: Boolean = false,
    val appendMultiTrail: Boolean = false,
)

object TrackerMapPointRouter {
    fun route(event: TrackPointEvent, plan: TrackerMapStreamingPlan): TrackerMapPointRoute {
        val trackerId = event.trackId.trim()
        if (trackerId.isEmpty()) return TrackerMapPointRoute(accepted = false)
        return when (event.source) {
            TrackPointSource.LOCAL_GPS -> routeLocal(trackerId, plan)
            TrackPointSource.REMOTE_STREAM -> routeRemote(trackerId, plan)
        }
    }

    private fun routeLocal(trackerId: String, plan: TrackerMapStreamingPlan): TrackerMapPointRoute {
        if (trackerId !in plan.locallyRecordedTrackerIds) return TrackerMapPointRoute(accepted = false)
        val appendSingle = plan.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            (plan.displayedTrackerId.isEmpty() || plan.displayedTrackerId == trackerId)
        return TrackerMapPointRoute(
            accepted = true,
            normalizedTrackerId = trackerId,
            appendSingleTrail = appendSingle,
            appendMultiTrail = trackerId in plan.localOverlayTrackerIds &&
                (plan.mode == TrackerMapDisplayMode.ALL_QUEUE || plan.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER),
        )
    }

    private fun routeRemote(trackerId: String, plan: TrackerMapStreamingPlan): TrackerMapPointRoute {
        if (trackerId in plan.locallyRecordedTrackerIds) return TrackerMapPointRoute(accepted = false)
        val appendSingle = plan.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            plan.displayedTrackerId.isNotEmpty() &&
            trackerId == plan.displayedTrackerId
        val appendMulti = (plan.mode == TrackerMapDisplayMode.ALL_QUEUE ||
            plan.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) &&
            trackerId in plan.acceptedRemoteTrackerIds
        val accepted = appendSingle || appendMulti
        return TrackerMapPointRoute(
            accepted = accepted,
            normalizedTrackerId = trackerId,
            updateRemoteLastPoint = accepted,
            appendSingleTrail = appendSingle,
            appendMultiTrail = appendMulti,
        )
    }
}
