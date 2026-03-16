package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.Group
import com.geovault.tracker.R

internal data class InfoCardUiState(
    val nameText: String,
    val coordsText: String,
    val lastUpdatedText: String,
    val viewInListContentDescription: String,
    val viewParamsContentDescription: String,
    val shouldRecenterOnOpen: Boolean,
    val shouldRefreshPointIcons: Boolean
)

internal object MapTrackerInfoCardController {
    fun computeUiState(
        selection: SelectedMapTracker,
        currentGroupForMap: Group?,
        wasInfoCardVisible: Boolean,
        selectionIdChanged: Boolean,
        context: Context
    ): InfoCardUiState {
        val nameText = selection.name.ifEmpty { context.getString(R.string.select_tracker) }
        val coordsText = MapSelectionUtils.formatCoords(selection.lat, selection.lon)
        val lastUpdatedText = MapSelectionUtils.formatLastUpdated(context, selection.lastUpdateMs)
        val viewInListContentDescription = when {
            currentGroupForMap != null -> context.getString(R.string.view_in_group_members)
            selection.isOwner -> context.getString(R.string.view_in_trackers_list)
            else -> context.getString(R.string.view_in_shared_list)
        }
        val viewParamsContentDescription = context.getString(R.string.map_tracker_info_view_params_content_description)
        val shouldRecenterOnOpen = !wasInfoCardVisible || selectionIdChanged
        return InfoCardUiState(
            nameText = nameText,
            coordsText = coordsText,
            lastUpdatedText = lastUpdatedText,
            viewInListContentDescription = viewInListContentDescription,
            viewParamsContentDescription = viewParamsContentDescription,
            shouldRecenterOnOpen = shouldRecenterOnOpen,
            shouldRefreshPointIcons = selectionIdChanged
        )
    }
}
