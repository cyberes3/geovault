package com.geovault.tracker.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerRuntimeStoreRecordingTest {

    @Test
    fun update_reflectsRecordingRuntimeGpsCollectingFlag() {
        val before = TrackerRuntimeStore.value.recording
        try {
            TrackerRuntimeStore.updateRecording {
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
            assertTrue(TrackerRuntimeStore.value.recording.gpsCollecting)
            TrackerRuntimeStore.updateRecording {
                it.copy(
                    recordingRuntime = it.recordingRuntime.copy(
                        gpsCollecting = false,
                        pausedForMotion = true,
                    ),
                )
            }
            assertFalse(TrackerRuntimeStore.value.recording.gpsCollecting)
        } finally {
            TrackerRuntimeStore.updateRecording { before }
        }
    }
}
