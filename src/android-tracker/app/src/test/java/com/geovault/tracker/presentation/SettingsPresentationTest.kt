package com.geovault.tracker.presentation

import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsDefaults
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.settings.TrackerSettingsState
import com.geovault.tracker.settings.TrackerTrackingProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPresentationTest {

    @Test
    fun withTrackerState_mergesLoadStateSettingsAndRevision() {
        val base = SettingsState(serverUrl = "https://example.com", isLoggedIn = true)
        val tracker = TrackerSettingsState(
            loadState = TrackerSettingsLoadState.Ready,
            settings = TrackerSettings(
                loggingIntervalSec = 42L,
                trackingProfile = TrackerTrackingProfile.WALKING,
                sendExtendedData = false,
            ),
            wasTrackingBeforeExit = false,
            schemaVersion = TrackerSettingsDefaults.schemaVersion,
            revision = 7L,
        )
        val merged = base.withTrackerState(tracker)
        assertEquals(TrackerSettingsLoadState.Ready, merged.trackerLoadState)
        assertEquals(42L, merged.trackerSettings.loggingIntervalSec)
        assertEquals(TrackerTrackingProfile.WALKING, merged.trackerSettings.trackingProfile)
        assertEquals(false, merged.trackerSettings.sendExtendedData)
        assertEquals(7L, merged.trackerRevision)
        assertEquals("https://example.com", merged.serverUrl)
        assertEquals(true, merged.isLoggedIn)
    }
}
