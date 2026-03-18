package com.geovault.tracker

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SelectedTrackerPrefsTest {
    @Test
    fun setSelectedTracker_roundTripIdAndName() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SelectedTrackerPrefs.clearSelectedTracker(context)

        SelectedTrackerPrefs.setSelectedTracker(context, " tracker-id ", " Tracker Name ")

        assertEquals("tracker-id", SelectedTrackerPrefs.selectedTrackerId(context))
        assertEquals("Tracker Name", SelectedTrackerPrefs.selectedTrackerName(context))
    }

    @Test
    fun clearSelectedTracker_resetsValues() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SelectedTrackerPrefs.setSelectedTracker(context, "tracker-id", "Tracker Name")

        SelectedTrackerPrefs.clearSelectedTracker(context)

        assertEquals("", SelectedTrackerPrefs.selectedTrackerId(context))
        assertEquals("", SelectedTrackerPrefs.selectedTrackerName(context))
    }
}

