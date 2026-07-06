package com.geovault.tracker.presentation

/**
 * Resolves what tapping a tracker marker does to [TrackerMapUiState] once a selection card has
 * been built for it.
 *
 * ORPHAN GUARD: live active fit in SINGLE_SESSION composes with (rather than replaces) an
 * existing selection lock -- see [TrackerMapLiveActiveFitPolicy]'s class doc. If the tapped
 * tracker doesn't match the currently-locked one, the lock is dropped the same way
 * [com.geovault.tracker.map.MapContextSubsystem]'s `toggleTrackerLock` drops it for a manual
 * lock change (via `withAllMapLocksDisabled()`). Dropping the lock here without also clearing
 * `liveActiveFitEnabled` would strand it silently enabled with no lock left to modify.
 */
object TrackerMapSelectionCardPolicy {
    fun applySelectionCard(
        state: TrackerMapUiState,
        selection: TrackerMapSelectionCard,
    ): TrackerMapUiState {
        val previousSelectionLockId = state.selectionLockTrackerId.trim()
        val nextSelectionLockId = previousSelectionLockId
            .takeIf { it.isNotEmpty() && it == selection.trackerId }
            .orEmpty()
        val droppedMismatchedLock = previousSelectionLockId.isNotEmpty() && nextSelectionLockId.isEmpty()
        return state.copy(
            isBottomCardVisible = TrackerMapViewModel.resolveBottomCardVisibilityForMarkerTap(hasSelectionCard = true),
            selectedMapTracker = selection,
            selectionLockTrackerId = nextSelectionLockId,
            liveActiveFitEnabled = if (droppedMismatchedLock) false else state.liveActiveFitEnabled,
        )
    }
}
