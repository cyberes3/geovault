package com.geovault.tracker.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingRuntimeStateStoreTest {

    @Test
    fun update_reflectsRecordingRuntimeGpsCollectingFlag() {
        val before = TrackingRuntimeStateStore.state.value
        try {
            TrackingRuntimeStateStore.update {
                it.copy(
                    isRunning = true,
                    selectedTrackerId = "t1",
                    recordingRuntime = it.recordingRuntime.copy(
                        sessionActive = true,
                        gpsCollecting = true,
                        pausedForMotion = false,
                    ),
                )
            }
            assertTrue(TrackingRuntimeStateStore.state.value.gpsCollecting)
            TrackingRuntimeStateStore.update {
                it.copy(
                    recordingRuntime = it.recordingRuntime.copy(
                        gpsCollecting = false,
                        pausedForMotion = true,
                    ),
                )
            }
            assertFalse(TrackingRuntimeStateStore.state.value.gpsCollecting)
        } finally {
            TrackingRuntimeStateStore.update { before }
        }
    }
}
