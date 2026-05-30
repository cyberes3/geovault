package com.geovault.tracker.presentation

import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsDefaults
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.settings.TrackerSettingsState
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPresentationTest {

    @Test
    fun withTrackerState_mergesLoadStateSettingsAndRevision() {
        val base = SettingsState()
        val tracker = TrackerSettingsState(
            loadState = TrackerSettingsLoadState.Ready,
            settings = TrackerSettings(
                sendExtendedData = false,
            ),
            wasTrackingBeforeExit = false,
            schemaVersion = TrackerSettingsDefaults.schemaVersion,
            revision = 7L,
        )
        val merged = base.withTrackerState(tracker)
        assertEquals(TrackerSettingsLoadState.Ready, merged.trackerLoadState)
        assertEquals(false, merged.trackerSettings.sendExtendedData)
        assertEquals(7L, merged.trackerRevision)
        assertEquals(base.infoMessage, merged.infoMessage)
    }
}
