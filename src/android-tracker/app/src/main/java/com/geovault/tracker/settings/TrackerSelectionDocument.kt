package com.geovault.tracker.settings

import android.content.SharedPreferences
import com.geovault.common.settings.GeoVaultLegacySettingsBlob
import kotlinx.serialization.Serializable

@Serializable
data class TrackerSelectionDocument(
    val selectedTrackerId: String = "",
    val selectedTrackerName: String = "",
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "tracker_selection.settings"
        const val LEGACY_PREFS_NAME = "geovault_prefs"
        const val KEY_SELECTED_TRACKER_ID = "selected_tracker_id"
        const val KEY_SELECTED_TRACKER_NAME = "selected_tracker_name"

        fun fromLegacy(blob: GeoVaultLegacySettingsBlob): TrackerSelectionDocument {
            return TrackerSelectionDocument(
                selectedTrackerId = blob.stringValues[KEY_SELECTED_TRACKER_ID].orEmpty().trim(),
                selectedTrackerName = blob.stringValues[KEY_SELECTED_TRACKER_NAME].orEmpty().trim(),
            )
        }

        fun fromLegacyPreferences(prefs: SharedPreferences): TrackerSelectionDocument {
            return TrackerSelectionDocument(
                selectedTrackerId = prefs.getString(KEY_SELECTED_TRACKER_ID, "").orEmpty().trim(),
                selectedTrackerName = prefs.getString(KEY_SELECTED_TRACKER_NAME, "").orEmpty().trim(),
            )
        }
    }
}
