package com.geovault.tracker.settings

import com.geovault.common.settings.GeoVaultLegacySettingsBlob
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerSelectionDocumentTest {

    @Test
    fun fromLegacy_readsSharedPreferenceKeys() {
        val document = TrackerSelectionDocument.fromLegacy(
            GeoVaultLegacySettingsBlob(
                stringValues = mapOf(
                    TrackerSelectionDocument.KEY_SELECTED_TRACKER_ID to " tracker-9 ",
                    TrackerSelectionDocument.KEY_SELECTED_TRACKER_NAME to " Field unit ",
                ),
            )
        )

        assertEquals("tracker-9", document.selectedTrackerId)
        assertEquals("Field unit", document.selectedTrackerName)
    }
}
