package com.geovault.tracker.fragments.map

import android.content.res.Resources
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.geovault.tracker.R

internal object MapTrackerHeaderUiHelper {
    fun applyLabelState(
        state: TrackerLabelState,
        resources: Resources,
        trackerLabelCard: View,
        trackerLabelIcon: ImageView,
        trackerNameLabel: TextView,
        lastUpdatedLabel: TextView,
        resetToTrackerButton: View,
        showAllTrackersButton: View,
        showAllTrackers: Boolean,
        getString: (Int) -> String,
        onHideCardClearDisplayed: () -> Unit,
        updateStreamingUi: () -> Unit
    ) {
        when (state) {
            is TrackerLabelState.GroupMode -> {
                trackerLabelCard.visibility = View.VISIBLE
                trackerLabelIcon.setImageResource(R.drawable.ic_groups)
                MapTrackerLabelController.applyLabelWidthConstraints(resources, trackerLabelCard, trackerNameLabel, lastUpdatedLabel)
                trackerNameLabel.text = state.labelText
                resetToTrackerButton.visibility = View.VISIBLE
                resetToTrackerButton.contentDescription = state.resetContentDescription
                updateStreamingUi()
                showAllTrackersButton.visibility = View.GONE
                showAllTrackersButton.contentDescription = state.showAllTrackersContentDescription
            }

            is TrackerLabelState.HideCardClearDisplayed -> {
                trackerLabelCard.visibility = View.GONE
                onHideCardClearDisplayed()
                updateStreamingUi()
                showAllTrackersButton.visibility = View.VISIBLE
                showAllTrackersButton.contentDescription = if (showAllTrackers) {
                    getString(R.string.show_selected_tracker)
                } else {
                    getString(R.string.show_all_trackers)
                }
            }

            is TrackerLabelState.ShowTrackerMode -> {
                trackerLabelCard.visibility = View.VISIBLE
                MapTrackerLabelController.applyLabelWidthConstraints(resources, trackerLabelCard, trackerNameLabel, lastUpdatedLabel)
                trackerLabelIcon.setImageResource(R.drawable.ic_chevron_track)
                trackerNameLabel.text = state.labelText
                resetToTrackerButton.visibility = state.resetButtonVisibility
                resetToTrackerButton.contentDescription = state.resetContentDescription
                updateStreamingUi()
                showAllTrackersButton.visibility = state.showAllTrackersVisibility
                showAllTrackersButton.contentDescription = state.showAllTrackersContentDescription
            }
        }
    }

    fun applyStreamingState(
        state: MapTrackerLabelController.StreamingLabelState,
        lastUpdatedLabel: TextView,
        clearCachedStreamingState: () -> Unit,
        updateBottomRightSpinner: () -> Unit
    ) {
        if (state.visible && state.labelText != null) {
            lastUpdatedLabel.visibility = View.VISIBLE
            lastUpdatedLabel.text = state.labelText
        } else {
            clearCachedStreamingState()
            lastUpdatedLabel.visibility = View.GONE
        }
        updateBottomRightSpinner()
    }
}
