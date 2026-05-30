package com.geovault.tracker.presentation

import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.TrackingUiStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateMergeTest {

    @Test
    fun merge_mapsRuntimeAndPermissions() {
        val runtime = TrackingRuntimeSnapshot(
            isRunning = true,
            recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "t1"),
            lifecycleState = TrackingLifecycleState.RUNNING,
            selectedTrackerId = "t1",
            selectedTrackerName = "Field truck",
            queuedPointsVisible = 4,
            pointsSentThisSession = 10,
            gpsProviderEnabled = true,
        )
        val perms = HomePermissionSnapshot(
            hasForegroundLocation = true,
            hasBackgroundLocation = true,
            hasPostNotifications = true,
        )
        val merged = mergeHomeUiState(runtime, perms, statusMessage = "hint")
        assertTrue(merged.isTracking)
        assertEquals(TrackingLifecycleState.RUNNING, merged.lifecycleState)
        assertEquals("t1", merged.selectedTrackerId)
        assertEquals("Field truck", merged.selectedTrackerDisplayName)
        assertEquals(4, merged.queuedPointsVisible)
        assertEquals(10, merged.pointsSentThisSession)
        assertTrue(merged.permissions.readyForTracking)
        assertEquals("hint", merged.statusMessage)
    }

    @Test
    fun merge_displayName_fallsBackToIdWhenNameBlank() {
        val runtime = TrackingRuntimeSnapshot(
            selectedTrackerId = "id-only",
            selectedTrackerName = "   ",
        )
        val merged = mergeHomeUiState(runtime, HomePermissionSnapshot(), statusMessage = "")
        assertEquals("id-only", merged.selectedTrackerDisplayName)
    }

    @Test
    fun merge_startupActiveRendersAsTrackingAndStarting() {
        val runtime = TrackingRuntimeSnapshot(
            recordingRuntime = RecordingRuntime(startupActive = true, selectedTrackerId = "t1"),
            lifecycleState = TrackingLifecycleState.STOPPED,
            selectedTrackerId = "t1",
            selectedTrackerName = "Field truck",
        )

        val merged = mergeHomeUiState(runtime, HomePermissionSnapshot(), statusMessage = "")

        assertTrue(merged.isTracking)
        assertEquals(TrackingLifecycleState.STARTING, merged.lifecycleState)
    }

    @Test
    fun merge_lockingShowsCurrentFixAccuracyInsteadOfHeldGoodAccuracy() {
        val runtime = TrackingRuntimeSnapshot(
            recordingRuntime = RecordingRuntime(sessionActive = true),
            uiStatus = TrackingUiStatus.LOCKING,
            lastAccuracyMeters = 8f,
            currentFixAccuracyMeters = 85f,
            effectiveAccuracyThresholdMeters = 50f,
        )

        val merged = mergeHomeUiState(runtime, HomePermissionSnapshot(), statusMessage = "")

        assertEquals(85f, merged.lastAccuracyMeters)
    }

    @Test
    fun merge_activeTrackingShowsHeldLastAccuracyWhenNotLocking() {
        val runtime = TrackingRuntimeSnapshot(
            recordingRuntime = RecordingRuntime(sessionActive = true),
            uiStatus = TrackingUiStatus.TRACKING_ACTIVE,
            lastAccuracyMeters = 8f,
            currentFixAccuracyMeters = 85f,
            effectiveAccuracyThresholdMeters = 50f,
        )

        val merged = mergeHomeUiState(runtime, HomePermissionSnapshot(), statusMessage = "")

        assertEquals(8f, merged.lastAccuracyMeters)
    }
}
