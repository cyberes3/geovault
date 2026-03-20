package com.geovault.tracker.fragments.map

import android.content.Context
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.geovault.tracker.R

internal sealed class TrackerLabelState {
    data class GroupMode(
        val labelText: String,
        val resetContentDescription: String
    ) : TrackerLabelState()

    object HideCardClearDisplayed : TrackerLabelState()
    object HideCardKeepDisplayed : TrackerLabelState()

    data class ShowTrackerMode(
        val labelText: String,
        val resetButtonVisibility: Int,
        val resetContentDescription: String
    ) : TrackerLabelState()
}

internal object MapTrackerLabelController {
    fun computeLabelState(
        mapViewContext: MapViewContext,
        showAllTrackers: Boolean,
        displayedTrackerId: String?,
        displayedTrackerName: String?,
        displayedGroupName: String?,
        selectedTrackerName: String,
        trackingRunning: Boolean,
        context: Context
    ): TrackerLabelState {
        val showingSingleTracker = !showAllTrackers &&
            mapViewContext != MapViewContext.GROUP &&
            !displayedTrackerId.isNullOrEmpty()
        if (mapViewContext == MapViewContext.GROUP) {
            return TrackerLabelState.GroupMode(
                labelText = displayedGroupName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.groups_title),
                resetContentDescription = context.getString(R.string.show_selected_tracker)
            )
        }
        if (trackingRunning && !showingSingleTracker) {
            return TrackerLabelState.HideCardKeepDisplayed
        }
        if (showAllTrackers) {
            return TrackerLabelState.ShowTrackerMode(
                labelText = context.getString(R.string.all_trackers),
                resetButtonVisibility = View.VISIBLE,
                resetContentDescription = context.getString(R.string.show_selected_tracker)
            )
        }
        if (!showingSingleTracker) {
            return TrackerLabelState.HideCardClearDisplayed
        }
        val labelName = displayedTrackerName?.takeIf { it.isNotBlank() }
            ?: selectedTrackerName.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.select_tracker)
        val resetVisibility = when {
            showAllTrackers -> View.GONE
            showingSingleTracker -> View.VISIBLE
            else -> View.GONE
        }
        return TrackerLabelState.ShowTrackerMode(
            labelText = labelName,
            resetButtonVisibility = resetVisibility,
            resetContentDescription = context.getString(R.string.show_selected_tracker)
        )
    }

    fun isStreaming(displayedTrackerId: String?): Boolean {
        return !displayedTrackerId.isNullOrEmpty()
    }

    internal data class StreamingLabelState(val visible: Boolean, val labelText: String?)

    fun computeStreamingLabelState(
        displayedTrackerId: String?,
        lastStreamedPointTimeMs: Long?,
        lastCachedUpdateTimeMs: Long?,
        context: Context
    ): StreamingLabelState {
        if (!isStreaming(displayedTrackerId)) {
            return StreamingLabelState(visible = false, labelText = null)
        }
        val effectiveTs = lastStreamedPointTimeMs ?: lastCachedUpdateTimeMs
        val labelText = formatStreamingLastUpdated(context, effectiveTs)
        return StreamingLabelState(visible = labelText != null, labelText = labelText)
    }

    fun formatStreamingLastUpdated(context: Context, effectiveTs: Long?): String? {
        if (effectiveTs == null) return null
        val diffMs = System.currentTimeMillis() - effectiveTs
        val diffSec = (diffMs / 1000).coerceAtLeast(0)
        val (n, unitResId) = when {
            diffSec < 60 -> {
                val n = diffSec.toInt()
                n to if (n == 1) R.string.last_updated_second else R.string.last_updated_seconds
            }
            diffSec < 3600 -> {
                val n = (diffSec / 60).toInt()
                n to if (n == 1) R.string.last_updated_minute else R.string.last_updated_minutes
            }
            diffSec < 86400 -> {
                val n = (diffSec / 3600).toInt()
                n to if (n == 1) R.string.last_updated_hour else R.string.last_updated_hours
            }
            else -> {
                val n = (diffSec / 86400).toInt()
                n to if (n == 1) R.string.last_updated_day else R.string.last_updated_days
            }
        }
        return context.getString(R.string.last_updated_streaming, n, context.getString(unitResId))
    }

    fun applyLabelWidthConstraints(
        resources: Resources,
        trackerLabelCard: View,
        trackerNameLabel: TextView,
        lastUpdatedLabel: TextView
    ) {
        val density = resources.displayMetrics.density
        val maxAllowedWidth = (resources.displayMetrics.widthPixels * 2) / 3
        trackerLabelCard.layoutParams = trackerLabelCard.layoutParams.apply {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        trackerNameLabel.maxWidth = maxAllowedWidth - (90 * density).toInt()
        val updatedFixedDesiredWidth = (160 * density).toInt()
        val cappedUpdatedWidth = updatedFixedDesiredWidth.coerceAtMost(maxAllowedWidth - (34 * density).toInt())
        lastUpdatedLabel.layoutParams = lastUpdatedLabel.layoutParams.apply {
            width = cappedUpdatedWidth
        }
        lastUpdatedLabel.maxWidth = cappedUpdatedWidth
    }
}
