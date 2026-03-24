package com.geovault.tracker

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.TrackingRuntimeStateStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SelectedTrackerManagerTest {
    @Test
    fun setSelectedTracker_updatesRuntimeStateWhenNotTracking() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        resetRuntimeState()
        SelectedTrackerPrefs.clearSelectedTracker(context)

        SelectedTrackerManager.setSelectedTracker(
            context = context,
            trackerId = "tracker-id",
            trackerName = "Tracker Name",
            restartTrackingIfRunning = false
        )

        val runtime = TrackingRuntimeStateStore.state.value
        assertEquals("tracker-id", runtime.selectedTrackerId)
        assertEquals("Tracker Name", runtime.selectedTrackerName)
        assertEquals("tracker-id", SelectedTrackerPrefs.selectedTrackerId(context))
        assertEquals("Tracker Name", SelectedTrackerPrefs.selectedTrackerName(context))
    }

    @Test
    fun clearSelectedTracker_updatesRuntimeAndStopsActiveTracking() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val application = context.applicationContext as Application
        resetRuntimeState(isRunning = true)
        SelectedTrackerPrefs.setSelectedTracker(context, "tracker-id", "Tracker Name")

        SelectedTrackerManager.clearSelectedTracker(context)

        val runtime = TrackingRuntimeStateStore.state.value
        assertEquals("", runtime.selectedTrackerId)
        assertEquals("", runtime.selectedTrackerName)
        assertEquals("", SelectedTrackerPrefs.selectedTrackerId(context))
        assertEquals("", SelectedTrackerPrefs.selectedTrackerName(context))

        val stopIntent = shadowOf(application).nextStartedService
        assertEquals(TrackingService.ACTION_STOP, stopIntent.action)
    }

    @Test
    fun setSelectedTracker_restartsTrackingWhenActive() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val application = context.applicationContext as Application
        resetRuntimeState(isRunning = true)
        SelectedTrackerPrefs.clearSelectedTracker(context)

        SelectedTrackerManager.setSelectedTracker(
            context = context,
            trackerId = "new-tracker",
            trackerName = "New Tracker",
            restartTrackingIfRunning = true
        )

        val stopIntent = shadowOf(application).nextStartedService
        assertEquals(TrackingService.ACTION_STOP, stopIntent.action)

        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        val startIntent = shadowOf(application).nextStartedService
        assertEquals(TrackingService.ACTION_START, startIntent.action)
    }

    private fun resetRuntimeState(isRunning: Boolean = false) {
        TrackingRuntimeStateStore.update {
            TrackingRuntimeSnapshot(isRunning = isRunning)
        }
    }
}
