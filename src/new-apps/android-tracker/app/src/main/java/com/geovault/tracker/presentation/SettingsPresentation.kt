package com.geovault.tracker.presentation

import com.geovault.tracker.settings.TrackerSettingsState

/**
 * Merges durable tracker settings into the settings screen model. Kept as a pure function for tests.
 */
internal fun SettingsState.withTrackerState(trackerState: TrackerSettingsState): SettingsState {
    return copy(
        trackerLoadState = trackerState.loadState,
        trackerSettings = trackerState.settings,
        trackerRevision = trackerState.revision,
    )
}
