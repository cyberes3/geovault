package com.geovault.tracker.presentation

/**
 * What a roster removal (tracker deleted, unshared, or an accepted share revoked
 * server-side) should do to the map's UI state.
 */
data class TrackerRosterRemovalOutcome(
    val nextState: TrackerMapUiState,
    val changed: Boolean,
    val shouldRefreshStreamTargets: Boolean,
)

/**
 * Resolves what must happen to [TrackerMapUiState] when a tracker id that the map currently
 * cares about — displayed, actively streamed, or merely targeted for streaming — drops out of
 * the roster entirely.
 *
 * Without this, the map's marker/trail for that tracker simply freezes on its last-known
 * position while [TrackerMapUiState.streamingStatus] can keep reading LIVE — nothing ever tells
 * the pipeline the tracker is gone, since deletion/unshare has no dedicated point-stream signal.
 *
 * Scope note: this intentionally does not touch `TrackingRuntimeSnapshot.selectedTrackerId` or
 * `SelectedTrackerPrefs` — that is the user's own recording-target selection, a distinct and
 * much higher-blast-radius concern (it can restart/stop active recording) that the map layer
 * must not mutate as a side effect of a roster event for some other tracker. If the removed
 * tracker happens to equal `selectedTrackerId` too, the runtime-state collector's existing
 * blank-`displayedTrackerId`-falls-back-to-`selectedTrackerId` behavior is unchanged — this is a
 * pre-existing property of that fallback, not a regression introduced here.
 */
object TrackerMapRosterRemovalPolicy {
    fun applyRemoval(state: TrackerMapUiState, removedTrackerId: String): TrackerRosterRemovalOutcome {
        val id = removedTrackerId.trim()
        if (id.isEmpty()) {
            return TrackerRosterRemovalOutcome(state, changed = false, shouldRefreshStreamTargets = false)
        }
        val wasDisplayed = state.displayedTrackerId.trim() == id
        val wasStreamed = id in state.streamTargetIds || id in state.activeStreamedTrackerIds
        val hadTrailData = id in state.allQueueTrailsByTracker || id in state.remoteLastPoints
        if (!wasDisplayed && !wasStreamed && !hadTrailData) {
            return TrackerRosterRemovalOutcome(state, changed = false, shouldRefreshStreamTargets = false)
        }

        var next = state.copy(
            allQueueTrailsByTracker = state.allQueueTrailsByTracker - id,
            remoteLastPoints = state.remoteLastPoints - id,
            activeStreamedTrackerIds = state.activeStreamedTrackerIds - id,
            streamTargetIds = state.streamTargetIds - id,
        )

        if (wasDisplayed) {
            val removedName = state.displayedTrackerName.trim()
            next = next.copy(
                displayedTrackerId = "",
                displayedTrackerName = "",
                trail = if (state.mode == TrackerMapDisplayMode.SINGLE_SESSION) emptyList() else next.trail,
                unavailableTrackerNotice = TrackerMapUnavailableNotice(trackerId = id, trackerName = removedName),
            )
        }

        if (state.selectionLockTrackerId.trim() == id) {
            next = next.withAllMapLocksDisabled()
        }
        if (state.selectedMapTracker?.trackerId == id) {
            next = next.withClearedMapSelectionCard()
        }

        return TrackerRosterRemovalOutcome(
            nextState = next,
            changed = true,
            shouldRefreshStreamTargets = wasStreamed,
        )
    }
}
