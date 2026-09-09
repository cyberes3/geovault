package com.geovault.tracker.positioning

import com.geovault.tracker.positioning.TrackingUiStatus
import com.geovault.tracker.positioning.TrackingUiStatusResolver
import org.junit.Assert.assertEquals
import org.junit.Test

/** UI status strings derived from runtime snapshot fields projected by {@code RuntimeProjectionSubsystem}. */
import com.geovault.tracker.runtime.RecordingSession
import com.geovault.tracker.runtime.TrackerRuntimeDocument
import com.geovault.tracker.positioning.TrackingRuntimeSnapshot
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

class PositioningStatusProjectionCharacterizationTest {

    @Test
    fun statusResolver_mapsPausedMotionWhileRunning() {
        assertEquals(
            TrackingUiStatus.PAUSED_FOR_MOTION,
            TrackingUiStatusResolver.resolve(
                isRunning = true,
                gpsProviderEnabled = true,
                gpsPaused = true,
                lastAccuracyMeters = 5f,
                effectiveAccuracyThresholdMeters = 20f,
            ),
        )
    }

    @Test
    fun statusResolver_mapsGoodFixWhileRunning() {
        assertEquals(
            TrackingUiStatus.TRACKING_ACTIVE,
            TrackingUiStatusResolver.resolve(
                isRunning = true,
                gpsProviderEnabled = true,
                gpsPaused = false,
                lastAccuracyMeters = 5f,
                effectiveAccuracyThresholdMeters = 20f,
            ),
        )
    }

    @Test
    fun document_isRecordingWhenSessionPresent() {
        val recording = TrackingRuntimeSnapshot(
            isRunning = true,
            selectedTrackerId = "t1",
            recordingRuntime = com.geovault.tracker.positioning.RecordingRuntime(
                sessionActive = true,
                selectedTrackerId = "t1",
            ),
        )
        val document = TrackerRuntimeDocument(recording = recording)
        assertNotNull(document.recording.session)
        assertEquals("t1", document.locallyRecordedTrackerId)
        assertNull(TrackerRuntimeDocument().recording.session)
        val unused: RecordingSession? = document.recording.session
        assertEquals("t1", unused?.trackerId)
    }
}
