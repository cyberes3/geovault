package com.geovault.tracker.presentation

import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse

/**
 * Stateless map visibility policy shared across ViewModels.
 */
object MapVisibilityTogglePolicy {
    fun toggleTracker(current: MapVisibilityResponse, trackerId: String): MapVisibilityRequest {
        val hidden = current.hidden_track_ids.toMutableSet()
        if (hidden.contains(trackerId)) hidden.remove(trackerId) else hidden.add(trackerId)
        return MapVisibilityRequest(
            hidden_track_ids = hidden.toList(),
            hidden_group_ids = current.hidden_group_ids,
        )
    }

    fun toggleGroup(current: MapVisibilityResponse, groupId: String): MapVisibilityRequest {
        val hidden = current.hidden_group_ids.toMutableSet()
        if (hidden.contains(groupId)) hidden.remove(groupId) else hidden.add(groupId)
        return MapVisibilityRequest(
            hidden_track_ids = current.hidden_track_ids,
            hidden_group_ids = hidden.toList(),
        )
    }
}

internal fun toggleTrackerInVisibility(current: MapVisibilityResponse, trackerId: String): MapVisibilityRequest {
    return MapVisibilityTogglePolicy.toggleTracker(current, trackerId)
}

internal fun toggleGroupInVisibility(current: MapVisibilityResponse, groupId: String): MapVisibilityRequest {
    return MapVisibilityTogglePolicy.toggleGroup(current, groupId)
}
