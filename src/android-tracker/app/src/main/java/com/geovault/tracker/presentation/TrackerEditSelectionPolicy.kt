package com.geovault.tracker.presentation

/**
 * Resolves the selected-tracker side effect for saving the edit-tracker dialog.
 *
 * The edit dialog's "set as selected tracker" checkbox is true by default when editing the
 * tracker that is already selected. Saving metadata for that tracker must therefore be an
 * idempotent metadata update, not a stop/restart of the active tracking session.
 */
object TrackerEditSelectionPolicy {
    fun resolve(input: TrackerEditSelectionInput): TrackerEditSelectionAction {
        val trackerId = input.editedTrackerId.trim()
        if (trackerId.isEmpty()) return TrackerEditSelectionAction.NoSelectionChangeUnselected
        val selectedTrackerId = input.selectedTrackerId.orEmpty().trim()
        val isAlreadySelected = selectedTrackerId == trackerId
        return when {
            input.setAsSelectedTracker && isAlreadySelected ->
                TrackerEditSelectionAction.SameSelectedTrackerSettingsOnly
            input.setAsSelectedTracker ->
                TrackerEditSelectionAction.SelectDifferentTracker
            isAlreadySelected ->
                TrackerEditSelectionAction.ClearSelectedTracker
            else ->
                TrackerEditSelectionAction.NoSelectionChangeUnselected
        }
    }
}

data class TrackerEditSelectionInput(
    val editedTrackerId: String,
    val selectedTrackerId: String?,
    val setAsSelectedTracker: Boolean,
)

sealed class TrackerEditSelectionAction {
    data object SameSelectedTrackerSettingsOnly : TrackerEditSelectionAction()
    data object SelectDifferentTracker : TrackerEditSelectionAction()
    data object ClearSelectedTracker : TrackerEditSelectionAction()
    data object NoSelectionChangeUnselected : TrackerEditSelectionAction()
}
