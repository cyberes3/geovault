package com.geovault.tracker.policy

import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.TrackingRuntimeStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteTrackPointIngressTest {

    @Before
    fun setUp() {
        RemoteTrackPointIngress.resetForTests()
        TrackingRuntimeStateStore.update {
            it.copy(
                isRunning = false,
                recordingRuntime = RecordingRuntime(),
                selectedTrackerId = "",
            )
        }
    }

    @Test
    fun process_invalidCoordinates_isDroppedAndCounted() {
        val result = RemoteTrackPointIngress.process(
            remoteEvent(lon = 500.0),
            nowMs = NOW_MS,
        )

        assertNull(result)
        assertEquals(1L, RemoteTrackPointIngress.diagnostics().droppedInvalidEvents)
    }

    @Test
    fun process_secondsTimestamp_normalizesBeforePolicy() {
        val result = RemoteTrackPointIngress.process(
            remoteEvent(timestampMs = NOW_MS / 1000L),
            nowMs = NOW_MS,
        )

        assertNotNull(result)
        assertEquals(NOW_MS, result!!.timestampMs)
        assertTrue(result.orderingKey > 0L)
    }

    @Test
    fun process_remoteForLocallyRecordedSelectedTracker_isDroppedAndCounted() {
        TrackingRuntimeStateStore.update {
            it.copy(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "selected"),
                selectedTrackerId = "selected",
            )
        }

        val result = RemoteTrackPointIngress.process(
            remoteEvent(trackId = "selected"),
            nowMs = NOW_MS,
        )

        assertNull(result)
        assertEquals(1L, RemoteTrackPointIngress.diagnostics().droppedLocalEchoEvents)
    }

    @Test
    fun process_remoteForUiSelectedButNotRecordedTracker_isAccepted() {
        TrackingRuntimeStateStore.update {
            it.copy(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "local"),
                selectedTrackerId = "selected",
            )
        }

        val result = RemoteTrackPointIngress.process(
            remoteEvent(trackId = "selected"),
            nowMs = NOW_MS,
        )

        assertNotNull(result)
        assertEquals(0L, RemoteTrackPointIngress.diagnostics().droppedLocalEchoEvents)
    }

    private fun remoteEvent(
        trackId: String = "remote",
        lon: Double = 20.0,
        lat: Double = 10.0,
        timestampMs: Long = NOW_MS,
    ): TrackPointEvent {
        return TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = trackId,
            lon = lon,
            lat = lat,
            timestampMs = timestampMs,
        )
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
    }
}
