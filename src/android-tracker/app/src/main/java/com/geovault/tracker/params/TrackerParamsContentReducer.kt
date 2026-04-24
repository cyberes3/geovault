package com.geovault.tracker.params

/**
 * Pure classification for which empty-state or grid to show, matching
 * [com.geovault.tracker.fragments.TrackerParamsFragment.bindTracker] / stream update rules.
 */
enum class TrackerParamsBodyKind {
    ShowingGrid,
    NoExtendedParams,
    WaitingForData,
}

object TrackerParamsContentReducer {

    fun resolve(
        latestPointParams: Map<String, Any?>,
        lastTimestampMs: Long?,
        lastPosition: Pair<Double, Double>?,
    ): TrackerParamsBodyKind {
        val hasStoredParams = latestPointParams.isNotEmpty()
        if (hasStoredParams) return TrackerParamsBodyKind.ShowingGrid
        if (lastTimestampMs != null || lastPosition != null) {
            return TrackerParamsBodyKind.NoExtendedParams
        }
        return TrackerParamsBodyKind.WaitingForData
    }
}
