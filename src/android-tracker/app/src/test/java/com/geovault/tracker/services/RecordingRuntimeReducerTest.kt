package com.geovault.tracker.services
import com.geovault.tracker.positioning.config.GpsRuntimeState

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRuntimeReducerTest {

    @Test
    fun fromInputs_runningGpsCollecting_setsLocalRecordingActive() {
        val runtime = RecordingRuntimeReducer.fromInputs(
            previous = RecordingRuntime(),
            sessionActive = true,
            startupActive = false,
            gpsState = GpsRuntimeState.RUNNING,
            gpsProviderEnabled = true,
            selectedTrackerId = "tracker",
        )

        assertTrue(runtime.sessionActive)
        assertTrue(runtime.gpsCollecting)
        assertTrue(runtime.localRecordingActive)
        assertFalse(runtime.pausedForMotion)
    }

    @Test
    fun fromInputs_motionPause_isNotGpsCollecting() {
        val runtime = RecordingRuntimeReducer.fromInputs(
            previous = RecordingRuntime(),
            sessionActive = true,
            startupActive = false,
            gpsState = GpsRuntimeState.PAUSED_FOR_MOTION,
            gpsProviderEnabled = true,
            selectedTrackerId = "tracker",
        )

        assertFalse(runtime.gpsCollecting)
        assertTrue(runtime.pausedForMotion)
        assertFalse(runtime.waitingForProviderWhilePaused)
    }

    @Test
    fun fromInputs_providerPaused_isDistinctFromMotionPause() {
        val runtime = RecordingRuntimeReducer.fromInputs(
            previous = RecordingRuntime(),
            sessionActive = true,
            startupActive = false,
            gpsState = GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED,
            gpsProviderEnabled = false,
            selectedTrackerId = "tracker",
        )

        assertFalse(runtime.gpsCollecting)
        assertFalse(runtime.pausedForMotion)
        assertTrue(runtime.waitingForProviderWhilePaused)
    }
}
