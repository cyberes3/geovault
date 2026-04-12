package com.geovault.tracker.presentation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.geovault.tracker.R

sealed interface TrackerMapTopLeftChipText {
    data class Resource(@param:StringRes val resId: Int) : TrackerMapTopLeftChipText
    data class Value(val value: String) : TrackerMapTopLeftChipText
}

sealed interface TrackerMapTopLeftChipUiModel {
    data object Hidden : TrackerMapTopLeftChipUiModel

    data class Visible(
        val mode: TrackerMapTopLeftChipMode,
        @param:DrawableRes val iconResId: Int,
        val title: TrackerMapTopLeftChipText,
        val subtitle: TrackerMapTopLeftChipText? = null,
        val showReset: Boolean,
        @param:StringRes val cardContentDescriptionResId: Int,
        @param:StringRes val resetContentDescriptionResId: Int,
    ) : TrackerMapTopLeftChipUiModel
}

enum class TrackerMapTopLeftChipMode {
    SINGLE_TRACKER,
    ALL_TRACKERS,
    GROUP,
}

class TrackerMapTopLeftChipMapper {
    fun map(state: TrackerMapUiState): TrackerMapTopLeftChipUiModel {
        val displayedTrackerId = state.displayedTrackerId.trim().ifBlank {
            state.runtime.selectedTrackerId.trim()
        }
        val selectedTrackerId = state.runtime.selectedTrackerId.trim()
        val isSingleTrackerMode = state.mode == TrackerMapDisplayMode.SINGLE_SESSION
        val showingSingleTracker = isSingleTrackerMode && displayedTrackerId.isNotEmpty()

        if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            if (state.runtime.isRunning) {
                return TrackerMapTopLeftChipUiModel.Hidden
            }
            return TrackerMapTopLeftChipUiModel.Visible(
                mode = TrackerMapTopLeftChipMode.GROUP,
                iconResId = R.drawable.ic_groups,
                title = resolveGroupTitle(state),
                subtitle = null,
                showReset = true,
                cardContentDescriptionResId = R.string.map_chip_tracker_card_content_description,
                resetContentDescriptionResId = R.string.show_selected_tracker,
            )
        }

        if (state.mode == TrackerMapDisplayMode.ALL_QUEUE) {
            if (state.runtime.isRunning) {
                return TrackerMapTopLeftChipUiModel.Hidden
            }
            return TrackerMapTopLeftChipUiModel.Visible(
                mode = TrackerMapTopLeftChipMode.ALL_TRACKERS,
                iconResId = R.drawable.ic_chevron_track,
                title = TrackerMapTopLeftChipText.Resource(R.string.all_trackers),
                subtitle = null,
                showReset = true,
                cardContentDescriptionResId = R.string.map_chip_tracker_card_content_description,
                resetContentDescriptionResId = R.string.show_selected_tracker,
            )
        }

        if (!showingSingleTracker) {
            return TrackerMapTopLeftChipUiModel.Hidden
        }

        val title = state.displayedTrackerName.trim()
            .ifBlank { state.runtime.selectedTrackerName.trim() }
            .takeIf { it.isNotEmpty() }
            ?.let { TrackerMapTopLeftChipText.Value(it) }
            ?: TrackerMapTopLeftChipText.Resource(R.string.select_tracker)

        val showReset = displayedTrackerId.isNotEmpty() && displayedTrackerId != selectedTrackerId
        return TrackerMapTopLeftChipUiModel.Visible(
            mode = TrackerMapTopLeftChipMode.SINGLE_TRACKER,
            iconResId = R.drawable.ic_chevron_track,
            title = title,
            subtitle = null,
            showReset = showReset,
            cardContentDescriptionResId = R.string.map_chip_tracker_card_content_description,
            resetContentDescriptionResId = R.string.show_selected_tracker,
        )
    }

    private fun resolveGroupTitle(state: TrackerMapUiState): TrackerMapTopLeftChipText {
        val currentGroupId = state.currentGroupId.trim()
        val groupName = state.groupModeOptions.firstOrNull { it.groupId == currentGroupId }
            ?.groupName
            ?.trim()
            .orEmpty()
        return if (groupName.isNotEmpty()) {
            TrackerMapTopLeftChipText.Value(groupName)
        } else {
            TrackerMapTopLeftChipText.Resource(R.string.groups_title)
        }
    }
}
