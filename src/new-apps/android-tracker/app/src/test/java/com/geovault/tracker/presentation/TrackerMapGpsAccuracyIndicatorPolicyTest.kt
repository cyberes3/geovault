package com.geovault.tracker.presentation

import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapGpsAccuracyIndicatorPolicyTest {

    @Test
    fun resolve_notRunning_hidesIndicator() {
        val result = TrackerMapGpsAccuracyIndicatorPolicy.resolve(
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
        val result = TrackerMapGpsAccuracyIndicatorPolicy.resolve(
            TrackingRuntimeSnapshot(
                isRunning = true,
                lastAccuracyMeters = null,
                effectiveAccuracyThresholdMeters = 20f,
            )
        )

        assertTrue(result.isVisible)
    }

    @Test
    fun resolve_running_accuracyAtThreshold_hidesIndicator() {
        val result = TrackerMapGpsAccuracyIndicatorPolicy.resolve(
            TrackingRuntimeSnapshot(
                isRunning = true,
                lastAccuracyMeters = 20f,
                effectiveAccuracyThresholdMeters = 20f,
            )
        )

        assertFalse(result.isVisible)
    }

    @Test
    fun resolve_running_accuracyAboveThreshold_showsIndicator() {
        val result = TrackerMapGpsAccuracyIndicatorPolicy.resolve(
            TrackingRuntimeSnapshot(
                isRunning = true,
                lastAccuracyMeters = 20.1f,
                effectiveAccuracyThresholdMeters = 20f,
            )
        )

        assertTrue(result.isVisible)
    }
}
