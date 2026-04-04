package com.geovault.tracker

import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.services.TrackingRuntimeStateStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SelectedTrackerManagerTest {

    @Test
    fun updateSelectedTrackerNameIfSelected_updatesPrefsAndRuntimeSnapshot() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val before = TrackingRuntimeStateStore.state.value
        try {
            SelectedTrackerPrefs.setSelectedTracker(
                context = context,
                trackerId = "tracker-1",
                trackerName = "Before Name"
            )
            SelectedTrackerManager.syncRuntimeSelectedTracker(context)

            SelectedTrackerManager.updateSelectedTrackerNameIfSelected(
                context = context,
                trackerId = "tracker-1",
                trackerName = "After Name"
            )

            assertEquals("After Name", SelectedTrackerPrefs.selectedTrackerName(context))
            assertEquals("After Name", TrackingRuntimeStateStore.state.value.selectedTrackerName)
            assertEquals("tracker-1", TrackingRuntimeStateStore.state.value.selectedTrackerId)
        } finally {
            TrackingRuntimeStateStore.update { before }
        }
    }

    @Test
    fun updateSelectedTrackerNameIfSelected_ignoresNonSelectedTracker() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val before = TrackingRuntimeStateStore.state.value
        try {
            SelectedTrackerPrefs.setSelectedTracker(
                context = context,
                trackerId = "tracker-1",
                trackerName = "Stable Name"
            )
            SelectedTrackerManager.syncRuntimeSelectedTracker(context)

            SelectedTrackerManager.updateSelectedTrackerNameIfSelected(
                context = context,
                trackerId = "tracker-2",
                trackerName = "Should Not Apply"
            )

            assertEquals("Stable Name", SelectedTrackerPrefs.selectedTrackerName(context))
            assertEquals("Stable Name", TrackingRuntimeStateStore.state.value.selectedTrackerName)
            assertEquals("tracker-1", TrackingRuntimeStateStore.state.value.selectedTrackerId)
        } finally {
            TrackingRuntimeStateStore.update { before }
        }
    }
}
