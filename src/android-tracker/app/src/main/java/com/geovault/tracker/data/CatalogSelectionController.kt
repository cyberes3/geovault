package com.geovault.tracker.data

import android.content.Context
import com.geovault.tracker.runtime.TrackerRuntimeCommands
import com.geovault.tracker.runtime.TrackerRuntimeEngine
import com.geovault.tracker.runtime.TrackerRuntimeStore

/**
 * Only selected-tracker writer. Prefs persist the id; [CatalogStateStore] is the in-process
 * document UI and recording join against.
 */
class CatalogSelectionController(
    private val catalog: CatalogStateStore,
) {
    fun selectedTrackerId(context: Context): String {
        val fromStore = catalog.state.value.selectedTrackerId.trim()
        if (fromStore.isNotEmpty()) return fromStore
        return SelectedTrackerPrefs.selectedTrackerId(context).trim()
    }

    fun selectedTrackerName(context: Context): String {
        return SelectedTrackerPrefs.selectedTrackerName(context)
    }

    fun seedFromPersist(context: Context) {
        catalog.setSelection(SelectedTrackerPrefs.selectedTrackerId(context))
    }

    fun setSelectedTracker(
        context: Context,
        trackerId: String,
        trackerName: String?,
        restartTrackingIfRunning: Boolean = true,
    ) {
        val normalized = trackerId.trim()
        val previous = selectedTrackerId(context)
        SelectedTrackerPrefs.setSelectedTracker(context, normalized, trackerName)
        catalog.setSelection(normalized)
        if (normalized.isNotEmpty() && normalized == previous) return
        if (restartTrackingIfRunning && TrackerRuntimeStore.value.isRecording) {
            TrackerRuntimeEngine.get(context).switchRecordingTarget(normalized)
        }
    }

    fun clearSelectedTracker(context: Context) {
        SelectedTrackerPrefs.clearSelectedTracker(context)
        catalog.setSelection("")
        if (TrackerRuntimeStore.value.isRecording) {
            TrackerRuntimeEngine.get(context).handle(
                TrackerRuntimeCommands.Stop(reason = "selected_tracker_cleared"),
            )
        }
    }

    fun updateSelectedTrackerNameIfSelected(context: Context, trackerId: String, trackerName: String?) {
        if (selectedTrackerId(context) != trackerId) return
        SelectedTrackerPrefs.updateSelectedTrackerName(context, trackerName)
    }

    companion object {
        fun persistedTrackerId(context: Context): String =
            SelectedTrackerPrefs.selectedTrackerId(context).trim()

        fun persistedTrackerName(context: Context): String =
            SelectedTrackerPrefs.selectedTrackerName(context)

        fun persistSelection(context: Context, trackerId: String, trackerName: String?) {
            SelectedTrackerPrefs.setSelectedTracker(context, trackerId, trackerName)
        }

        fun clearPersistedSelection(context: Context) {
            SelectedTrackerPrefs.clearSelectedTracker(context)
        }
    }
}
