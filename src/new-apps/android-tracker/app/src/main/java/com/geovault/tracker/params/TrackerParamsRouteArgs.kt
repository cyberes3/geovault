package com.geovault.tracker.params

import com.geovault.tracker.Tracker
import com.geovault.tracker.presentation.TrackerMapSelectionCard

data class TrackerParamsSeed(
    val displayName: String,
    val lastUpdateMs: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val initialParams: Map<String, Any?>?,
    val isOwner: Boolean,
)

data class TrackerParamsRouteArgs(
    val trackerId: String,
    val seed: TrackerParamsSeed,
)

fun Tracker.toTrackerParamsRouteArgs(): TrackerParamsRouteArgs {
    val point = last_point
    val latitude = point?.getOrNull(1)
    val longitude = point?.getOrNull(0)
    val pointEpoch = point?.getOrNull(2)?.toLong()?.let { raw ->
        if (raw < 1_000_000_000_000L) raw * 1000L else raw
    }
    val lastMs = pointEpoch ?: updated_at
    return TrackerParamsRouteArgs(
        trackerId = id,
        seed = TrackerParamsSeed(
            displayName = name.ifBlank { id },
            lastUpdateMs = lastMs?.takeIf { it >= 0 },
            latitude = latitude,
            longitude = longitude,
            initialParams = point_params?.lastOrNull(),
            isOwner = isOwner(),
        ),
    )
}

fun TrackerMapSelectionCard.toTrackerParamsRouteArgs(): TrackerParamsRouteArgs {
    return TrackerParamsRouteArgs(
        trackerId = trackerId,
        seed = TrackerParamsSeed(
            displayName = trackerName.ifBlank { trackerId },
            lastUpdateMs = lastUpdatedMs?.takeIf { it > 0 },
            latitude = latitude,
            longitude = longitude,
            initialParams = null,
            isOwner = isOwned,
        ),
    )
}
