package com.geovault.tracker.params

import com.geovault.tracker.Tracker
import com.geovault.tracker.presentation.TrackerMapSelectionCard
import com.geovault.tracker.toLooseMap

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
            initialParams = point_params?.lastOrNull()?.toLooseMap(),
            isOwner = isOwner(),
        ),
    )
}

fun TrackerMapSelectionCard.toTrackerParamsRouteArgs(
    tracker: Tracker? = null,
): TrackerParamsRouteArgs {
    return paramsRouteArgs(
        tracker = tracker,
        trackerId = trackerId,
        displayName = trackerName,
        lastUpdateMs = lastUpdatedMs?.takeIf { it > 0 },
        latitude = latitude,
        longitude = longitude,
        isOwner = isOwned,
    )
}

fun paramsRouteArgs(
    tracker: Tracker?,
    trackerId: String,
    displayName: String,
    lastUpdateMs: Long?,
    latitude: Double?,
    longitude: Double?,
    isOwner: Boolean,
): TrackerParamsRouteArgs {
    val catalog = tracker?.toTrackerParamsRouteArgs()?.seed
    return TrackerParamsRouteArgs(
        trackerId = trackerId,
        seed = TrackerParamsSeed(
            displayName = displayName.ifBlank { catalog?.displayName.orEmpty() }.ifBlank { trackerId },
            lastUpdateMs = lastUpdateMs ?: catalog?.lastUpdateMs,
            latitude = latitude ?: catalog?.latitude,
            longitude = longitude ?: catalog?.longitude,
            initialParams = catalog?.initialParams,
            isOwner = isOwner,
        ),
    )
}
