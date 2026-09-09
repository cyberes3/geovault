package com.geovault.tracker.settings

enum class TrackerSettingsLoadState {
    Loading,
    Ready,
    Error
}

data class TrackerSettingsState(
    val loadState: TrackerSettingsLoadState,
    val settings: TrackerSettings,
    val schemaVersion: Int,
    val revision: Long
) {
    val isReady: Boolean
        get() = loadState == TrackerSettingsLoadState.Ready

    companion object {
        fun loading(): TrackerSettingsState {
            return TrackerSettingsState(
                loadState = TrackerSettingsLoadState.Loading,
                settings = TrackerSettingsDefaults.baseline,
                schemaVersion = TrackerSettingsDefaults.schemaVersion,
                revision = 0L
            )
        }
    }
}
