package com.geovault.tracker.presentation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.ui.TrackerPointTimestamps

sealed interface TrackerMapTopLeftChipText {
    data class Resource(@param:StringRes val resId: Int) : TrackerMapTopLeftChipText
    data class Value(val value: String) : TrackerMapTopLeftChipText
    data class RelativeLastData(
        val lastDataEpochMs: Long,
        val serverMetadataUpdatedAtMs: Long?,
        val lastPointParamsMs: Long?,
    ) : TrackerMapTopLeftChipText
}

sealed interface TrackerMapTopLeftChipUiModel {
    data object Hidden : TrackerMapTopLeftChipUiModel

    data class Visible(
        val mode: TrackerMapTopLeftChipMode,
        @param:DrawableRes val iconResId: Int,
        val title: TrackerMapTopLeftChipText,
        val userLabel: String? = null,
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
    fun map(
        state: TrackerMapUiState,
        roster: List<Tracker>,
        acceptedRemoteTrackerIds: Set<String>,
    ): TrackerMapTopLeftChipUiModel {
        val displayedTrackerId = state.displayedTrackerId.trim().ifBlank {
            state.runtime.selectedTrackerId.trim()
        }
        val selectedTrackerId = state.runtime.selectedTrackerId.trim()
        val isSingleTrackerMode = state.mode == TrackerMapDisplayMode.SINGLE_SESSION
        val showingSingleTracker = isSingleTrackerMode && displayedTrackerId.isNotEmpty()

        if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
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
            // UNAVAILABLE-NOTICE: `state.unavailableTrackerNotice` is only consulted while the raw
            // `displayedTrackerId` (not the selected-tracker fallback above) is still blank — the
            // moment any explicit selection sets a new displayed tracker, this branch is bypassed
            // and the notice is left to go stale in state rather than needing to be cleared at
            // every one of those call sites. See `TrackerMapRosterRemovalPolicy`.
            val notice = state.unavailableTrackerNotice
            if (isSingleTrackerMode && state.displayedTrackerId.isBlank() && notice != null) {
                return TrackerMapTopLeftChipUiModel.Visible(
                    mode = TrackerMapTopLeftChipMode.SINGLE_TRACKER,
                    iconResId = R.drawable.ic_chevron_track,
                    title = notice.trackerName.trim().takeIf { it.isNotEmpty() }
                        ?.let { TrackerMapTopLeftChipText.Value(it) }
                        ?: TrackerMapTopLeftChipText.Resource(R.string.select_tracker),
                    subtitle = TrackerMapTopLeftChipText.Resource(R.string.tracker_no_longer_available),
                    showReset = selectedTrackerId.isNotEmpty(),
                    cardContentDescriptionResId = R.string.map_chip_tracker_card_content_description,
                    resetContentDescriptionResId = R.string.show_selected_tracker,
                )
            }
            return TrackerMapTopLeftChipUiModel.Hidden
        }

        val title = state.displayedTrackerName.trim()
            .ifBlank { state.runtime.selectedTrackerName.trim() }
            .takeIf { it.isNotEmpty() }
            ?.let { TrackerMapTopLeftChipText.Value(it) }
            ?: TrackerMapTopLeftChipText.Resource(R.string.select_tracker)

        val effectiveDisplayedTrackerId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state)
        val tracker = roster.firstOrNull { it.id == effectiveDisplayedTrackerId }
        val isStreamingDisplayedTracker = effectiveDisplayedTrackerId.isNotEmpty() &&
            (effectiveDisplayedTrackerId in state.activeStreamedTrackerIds ||
                effectiveDisplayedTrackerId in state.streamTargetIds)
        val userLabel = if (isStreamingDisplayedTracker) {
            tracker?.owner_email?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val showReset = displayedTrackerId.isNotEmpty() && displayedTrackerId != selectedTrackerId
        val subtitle: TrackerMapTopLeftChipText? = when {
            selectedTrackerId.isNotEmpty() &&
                displayedTrackerId == selectedTrackerId -> null
            else -> {
                val lastMs = TrackerLastReportedAtPolicy.resolve(
                    trackerId = effectiveDisplayedTrackerId,
                    runtime = state.runtime,
                    resolverLastUpdatedMs = TrackerMapLastPointResolver.resolve(
                        state,
                        effectiveDisplayedTrackerId,
                        tracker,
                        acceptedRemoteTrackerIds,
                    )?.lastUpdatedMs,
                )
                if (lastMs == null) {
                    TrackerMapTopLeftChipText.Resource(R.string.waiting_for_data)
                } else {
                    val serverAt = tracker?.let(TrackerPointTimestamps::serverMetadataUpdatedAtMs)
                    TrackerMapTopLeftChipText.RelativeLastData(
                        lastDataEpochMs = lastMs,
                        serverMetadataUpdatedAtMs = serverAt,
                        lastPointParamsMs = tracker?.let(TrackerPointTimestamps::lastPointParamsMs),
                    )
                }
            }
        }
        return TrackerMapTopLeftChipUiModel.Visible(
            mode = TrackerMapTopLeftChipMode.SINGLE_TRACKER,
            iconResId = R.drawable.ic_chevron_track,
            title = title,
            userLabel = userLabel,
            subtitle = subtitle,
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
