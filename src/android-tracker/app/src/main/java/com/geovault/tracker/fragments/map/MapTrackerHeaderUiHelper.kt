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
            }

            is TrackerLabelState.HideCardClearDisplayed -> {
                trackerLabelCard.visibility = View.GONE
                onHideCardClearDisplayed()
                updateStreamingUi()
            }

            is TrackerLabelState.HideCardKeepDisplayed -> {
                trackerLabelCard.visibility = View.GONE
                updateStreamingUi()
            }

            is TrackerLabelState.ShowTrackerMode -> {
                trackerLabelCard.visibility = View.VISIBLE
                MapTrackerLabelController.applyLabelWidthConstraints(resources, trackerLabelCard, trackerNameLabel, lastUpdatedLabel)
                trackerLabelIcon.setImageResource(R.drawable.ic_chevron_track)
                trackerNameLabel.text = state.labelText
                resetToTrackerButton.visibility = state.resetButtonVisibility
                resetToTrackerButton.contentDescription = state.resetContentDescription
                updateStreamingUi()
            }
        }
    }

    fun applyStreamingState(
        state: MapTrackerLabelController.StreamingLabelState,
        lastUpdatedLabel: TextView,
        clearCachedStreamingState: () -> Unit,
        updateBottomRightSpinner: () -> Unit
    ) {
        lastUpdatedLabel.visibility = View.GONE
        if (!state.visible) {
            clearCachedStreamingState()
        }
        updateBottomRightSpinner()
    }
}
