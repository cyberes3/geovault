package com.geovault.tracker.fragments.map

enum class MapHistoryClearAction {
    REFRESH_GROUP_OR_ALL,
    REFRESH_ALL,
    REFRESH_DISPLAYED_SINGLE_FORCE_REPLACE,
    REFRESH_SELECTED_SINGLE_FORCE_REPLACE,
    NO_OP
}

data class MapHistoryClearInput(
    val clearedTrackerId: String,
    val showAllTrackers: Boolean,
    val mapViewContext: MapViewContext,
    val displayedTrackerId: String?,
    val selectedTrackerId: String
)

object MapHistoryClearPolicy {
    fun resolve(input: MapHistoryClearInput): MapHistoryClearAction {
        if (input.mapViewContext == MapViewContext.GROUP) {
            return MapHistoryClearAction.REFRESH_GROUP_OR_ALL
        }
        if (input.showAllTrackers) {
            return MapHistoryClearAction.REFRESH_ALL
        }
        if (input.clearedTrackerId == input.displayedTrackerId) {
            return MapHistoryClearAction.REFRESH_DISPLAYED_SINGLE_FORCE_REPLACE
        }
        if (input.displayedTrackerId.isNullOrEmpty() && input.clearedTrackerId == input.selectedTrackerId) {
            return MapHistoryClearAction.REFRESH_SELECTED_SINGLE_FORCE_REPLACE
        }
        return MapHistoryClearAction.NO_OP
    }
}
