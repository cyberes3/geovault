package com.geovault.tracker.presentation

enum class TrackerEditSelectionAction {
    None,
    SelectEditedTracker,
    ClearSelectedTracker,
}

data class TrackerEditSelectionDecision(
    val action: TrackerEditSelectionAction,
    val shouldRestartTracking: Boolean,
)

object TrackerEditSelectionPolicy {
    fun resolve(
        editedTrackerId: String,
        selectedTrackerId: String,
        setAsSelectedTracker: Boolean,
    ): TrackerEditSelectionDecision {
        val edited = editedTrackerId.trim()
        val selected = selectedTrackerId.trim()
        if (edited.isEmpty()) {
            return TrackerEditSelectionDecision(
                action = TrackerEditSelectionAction.None,
                shouldRestartTracking = false,
            )
        }
        if (setAsSelectedTracker) {
            return TrackerEditSelectionDecision(
                action = if (selected == edited) {
                    TrackerEditSelectionAction.None
                } else {
                    TrackerEditSelectionAction.SelectEditedTracker
                },
                shouldRestartTracking = selected != edited,
            )
        }
        return TrackerEditSelectionDecision(
            action = if (selected == edited) {
                TrackerEditSelectionAction.ClearSelectedTracker
            } else {
                TrackerEditSelectionAction.None
            },
            shouldRestartTracking = false,
        )
    }
}
