package com.geovault.tracker.fragments.map

internal data class SingleTrackerHistoryApplyInput(
    val forceReplace: Boolean,
    val normalizedCoordCount: Int,
    val hasTrackPoints: Boolean,
    val trackingActive: Boolean,
    val isExternalStreaming: Boolean,
    val isSelectedDefaultTrackerMode: Boolean
)

internal data class SingleTrackerHistoryApplyDecision(
    val shouldClearForEmptyForceReplace: Boolean,
    val shouldApplyGeometry: Boolean
)

internal object MapSingleTrackerHistoryPolicy {
    fun decide(input: SingleTrackerHistoryApplyInput): SingleTrackerHistoryApplyDecision {
        val hasHistoryPoints = input.normalizedCoordCount > 0
        val shouldClearForEmptyForceReplace = input.forceReplace && !hasHistoryPoints
        val shouldMergeTrackingHistory = input.trackingActive && input.isSelectedDefaultTrackerMode
        val shouldApplyGeometry = hasHistoryPoints && (
            input.forceReplace ||
                !input.hasTrackPoints ||
                (input.isExternalStreaming && (!input.trackingActive || shouldMergeTrackingHistory))
            )
        return SingleTrackerHistoryApplyDecision(
            shouldClearForEmptyForceReplace = shouldClearForEmptyForceReplace,
            shouldApplyGeometry = shouldApplyGeometry
        )
    }
}
