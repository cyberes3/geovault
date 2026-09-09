package com.geovault.tracker.presentation

import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.positioning.RecordingRuntime
import com.geovault.tracker.positioning.TrackingRuntimeSnapshot
import com.geovault.tracker.positioning.TrackingUiStatus
import com.geovault.tracker.runtime.TrackerRuntimeDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateMergeTest {

    @Test
    fun merge_mapsRuntimeAndPermissions() {
        val document = TrackerRuntimeDocument(
            recording = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "t1"),
                lifecycleState = TrackingLifecycleState.RUNNING,
                selectedTrackerId = "t1",
                selectedTrackerName = "Field truck",
                queuedPointsVisible = 4,
                pointsSentThisSession = 10,
                gpsProviderEnabled = true,
            ),
        )
        val perms = HomePermissionSnapshot(
            hasForegroundLocation = true,
            hasBackgroundLocation = true,
            hasPostNotifications = true,
        )
        val merged = mergeHomeUiState(
            document = document,
            permissions = perms,
            selectedTrackerId = "t1",
            selectedTrackerName = "Field truck",
        )
        assertTrue(merged.isTracking)
        assertEquals(TrackingLifecycleState.RUNNING, merged.lifecycleState)
        assertEquals("t1", merged.selectedTrackerId)
        assertEquals("Field truck", merged.selectedTrackerDisplayName)
        assertEquals(4, merged.queuedPointsVisible)
        assertEquals(10, merged.pointsSentThisSession)
        assertTrue(merged.permissions.readyForTracking)
    }

    @Test
    fun merge_displayName_fallsBackToIdWhenNameBlank() {
        val document = TrackerRuntimeDocument(
            recording = TrackingRuntimeSnapshot(
                selectedTrackerId = "id-only",
                selectedTrackerName = "   ",
            ),
        )
        val merged = mergeHomeUiState(
            document = document,
            permissions = HomePermissionSnapshot(),
            selectedTrackerId = "id-only",
            selectedTrackerName = "   ",
        )
        assertEquals("id-only", merged.selectedTrackerDisplayName)
    }

    @Test
    fun merge_startupActiveRendersAsTrackingAndStarting() {
        val document = TrackerRuntimeDocument(
            recording = TrackingRuntimeSnapshot(
                recordingRuntime = RecordingRuntime(startupActive = true, selectedTrackerId = "t1"),
                lifecycleState = TrackingLifecycleState.STOPPED,
                selectedTrackerId = "t1",
                selectedTrackerName = "Field truck",
            ),
        )

        val merged = mergeHomeUiState(
            document = document,
            permissions = HomePermissionSnapshot(),
            selectedTrackerId = "t1",
            selectedTrackerName = "Field truck",
        )

        assertTrue(merged.isTracking)
        assertEquals(TrackingLifecycleState.STARTING, merged.lifecycleState)
    }

    @Test
    fun merge_lockingShowsCurrentFixAccuracyInsteadOfHeldGoodAccuracy() {
        val document = TrackerRuntimeDocument(
            recording = TrackingRuntimeSnapshot(
                recordingRuntime = RecordingRuntime(sessionActive = true),
                uiStatus = TrackingUiStatus.LOCKING,
                lastAccuracyMeters = 8f,
                currentFixAccuracyMeters = 85f,
                effectiveAccuracyThresholdMeters = 50f,
            ),
        )

        val merged = mergeHomeUiState(document, HomePermissionSnapshot())

        assertEquals(85f, merged.lastAccuracyMeters)
    }

    @Test
    fun merge_sparseTrackingEnabled_isPassedThrough() {
        val merged = mergeHomeUiState(
            document = TrackerRuntimeDocument(),
            permissions = HomePermissionSnapshot(),
            sparseTrackingEnabled = true,
        )

        assertTrue(merged.sparseTrackingEnabled)
    }

    @Test
    fun merge_activeTrackingShowsHeldLastAccuracyWhenNotLocking() {
        val document = TrackerRuntimeDocument(
            recording = TrackingRuntimeSnapshot(
                recordingRuntime = RecordingRuntime(sessionActive = true),
                uiStatus = TrackingUiStatus.TRACKING_ACTIVE,
                lastAccuracyMeters = 8f,
                currentFixAccuracyMeters = 85f,
                effectiveAccuracyThresholdMeters = 50f,
            ),
        )

        val merged = mergeHomeUiState(document, HomePermissionSnapshot())

        assertEquals(8f, merged.lastAccuracyMeters)
    }

    @Test
    fun merge_usesCatalogSelectionWhenProvided() {
        val document = TrackerRuntimeDocument(
            recording = TrackingRuntimeSnapshot(
                selectedTrackerId = "runtime-id",
                selectedTrackerName = "Runtime name",
            ),
        )
        val merged = mergeHomeUiState(
            document = document,
            permissions = HomePermissionSnapshot(),
            selectedTrackerId = "catalog-id",
            selectedTrackerName = "Catalog name",
        )
        assertEquals("catalog-id", merged.selectedTrackerId)
        assertEquals("Catalog name", merged.selectedTrackerDisplayName)
    }
}
