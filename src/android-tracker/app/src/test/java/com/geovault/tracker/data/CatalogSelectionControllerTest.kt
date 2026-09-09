package com.geovault.tracker.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.positioning.RecordingRuntime
import com.geovault.tracker.runtime.TrackerRuntimeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CatalogSelectionControllerTest {
    private fun selection(context: Context): CatalogSelectionController {
        return TrackerAppServices.from(context.applicationContext as Application)
            .catalogSelectionController()
    }

    private fun catalog(context: Context): CatalogStateStore {
        return TrackerAppServices.from(context.applicationContext as Application)
            .catalogStateStore()
    }

    @Test
    fun updateSelectedTrackerNameIfSelected_updatesPrefs() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        try {
            CatalogSelectionController.persistSelection(
                context = context,
                trackerId = "tracker-1",
                trackerName = "Before Name"
            )
            selection(context).seedFromPersist(context)

            selection(context).updateSelectedTrackerNameIfSelected(
                context = context,
                trackerId = "tracker-1",
                trackerName = "After Name"
            )

            assertEquals("After Name", CatalogSelectionController.persistedTrackerName(context))
            assertEquals("tracker-1", catalog(context).state.value.selectedTrackerId)
        } finally {
            CatalogSelectionController.clearPersistedSelection(context)
        }
    }

    @Test
    fun updateSelectedTrackerNameIfSelected_ignoresNonSelectedTracker() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        try {
            CatalogSelectionController.persistSelection(
                context = context,
                trackerId = "tracker-1",
                trackerName = "Stable Name"
            )
            selection(context).seedFromPersist(context)

            selection(context).updateSelectedTrackerNameIfSelected(
                context = context,
                trackerId = "tracker-2",
                trackerName = "Should Not Apply"
            )

            assertEquals("Stable Name", CatalogSelectionController.persistedTrackerName(context))
            assertEquals("tracker-1", catalog(context).state.value.selectedTrackerId)
        } finally {
            CatalogSelectionController.clearPersistedSelection(context)
        }
    }

    @Test
    fun setSelectedTracker_sameId_updatesNameWithoutSwitchingRecording() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val before = TrackerRuntimeStore.value.recording
        try {
            CatalogSelectionController.persistSelection(
                context = context,
                trackerId = "tracker-1",
                trackerName = "Before Name"
            )
            selection(context).seedFromPersist(context)
            TrackerRuntimeStore.updateRecording {
                it.copy(
                    isRunning = true,
                    recordingRuntime = RecordingRuntime(
                        sessionActive = true,
                        selectedTrackerId = "tracker-1",
                    ),
                    selectedTrackerId = "tracker-1",
                    selectedTrackerName = "Before Name",
                )
            }

            selection(context).setSelectedTracker(
                context = context,
                trackerId = "tracker-1",
                trackerName = "After Name",
                restartTrackingIfRunning = true,
            )

            assertEquals("After Name", CatalogSelectionController.persistedTrackerName(context))
            assertEquals("tracker-1", catalog(context).state.value.selectedTrackerId)
            assertEquals("Before Name", TrackerRuntimeStore.value.recording.selectedTrackerName)
            assertEquals("tracker-1", TrackerRuntimeStore.value.recording.selectedTrackerId)
            assertTrue(TrackerRuntimeStore.value.recording.isRunning)
            assertTrue(TrackerRuntimeStore.value.recording.recordingRuntime.sessionActive)
        } finally {
            TrackerRuntimeStore.updateRecording { before }
            CatalogSelectionController.clearPersistedSelection(context)
        }
    }

    @Test
    fun setSelectedTracker_samePersistedId_doesNotWriteRecordingSnapshot() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val before = TrackerRuntimeStore.value.recording
        try {
            CatalogSelectionController.persistSelection(
                context = context,
                trackerId = "tracker-1",
                trackerName = "Before Name"
            )
            selection(context).seedFromPersist(context)
            TrackerRuntimeStore.updateRecording {
                it.copy(
                    isRunning = true,
                    recordingRuntime = RecordingRuntime(
                        sessionActive = true,
                        selectedTrackerId = "",
                    ),
                    selectedTrackerId = "",
                    selectedTrackerName = "",
                )
            }

            selection(context).setSelectedTracker(
                context = context,
                trackerId = "tracker-1",
                trackerName = "After Name",
                restartTrackingIfRunning = true,
            )

            assertEquals("tracker-1", CatalogSelectionController.persistedTrackerId(context))
            assertEquals("After Name", CatalogSelectionController.persistedTrackerName(context))
            assertEquals("tracker-1", catalog(context).state.value.selectedTrackerId)
            assertEquals("", TrackerRuntimeStore.value.recording.selectedTrackerId)
            assertEquals("", TrackerRuntimeStore.value.recording.selectedTrackerName)
            assertTrue(TrackerRuntimeStore.value.recording.isRunning)
        } finally {
            TrackerRuntimeStore.updateRecording { before }
            CatalogSelectionController.clearPersistedSelection(context)
        }
    }
}
