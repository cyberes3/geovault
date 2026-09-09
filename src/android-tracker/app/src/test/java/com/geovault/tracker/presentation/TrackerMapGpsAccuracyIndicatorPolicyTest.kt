package com.geovault.tracker.presentation

import com.geovault.tracker.positioning.RecordingRuntime
import com.geovault.tracker.positioning.TrackingRuntimeSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import com.geovault.tracker.map.MapRenderMath
class TrackerMapGpsAccuracyIndicatorPolicyTest {

    @Test
    fun resolve_notRunning_hidesIndicator() {
        val result = MapRenderMath.resolveGpsAccuracyIndicator(
            TrackingRuntimeSnapshot(
                isRunning = false,
                lastAccuracyMeters = 22f,
                effectiveAccuracyThresholdMeters = 10f,
            )
        )

        assertFalse(result.isVisible)
    }

    @Test
    fun resolve_running_nullAccuracy_showsIndicator() {
        val result = MapRenderMath.resolveGpsAccuracyIndicator(
            TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, gpsCollecting = true),
                lastAccuracyMeters = null,
                effectiveAccuracyThresholdMeters = 20f,
            )
        )

        assertTrue(result.isVisible)
    }

    @Test
    fun resolve_running_accuracyAtThreshold_hidesIndicator() {
        val result = MapRenderMath.resolveGpsAccuracyIndicator(
            TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, gpsCollecting = true),
                lastAccuracyMeters = 20f,
                effectiveAccuracyThresholdMeters = 20f,
            )
        )

        assertFalse(result.isVisible)
    }

    @Test
    fun resolve_running_accuracyAboveThreshold_showsIndicator() {
        val result = MapRenderMath.resolveGpsAccuracyIndicator(
            TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, gpsCollecting = true),
                lastAccuracyMeters = 20.1f,
                effectiveAccuracyThresholdMeters = 20f,
            )
        )

        assertTrue(result.isVisible)
    }
}
