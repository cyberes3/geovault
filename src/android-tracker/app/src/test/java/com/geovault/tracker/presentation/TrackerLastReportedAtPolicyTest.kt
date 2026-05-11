package com.geovault.tracker.presentation

import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerLastReportedAtPolicyTest {

    @Test
    fun `local tracker with successful upload returns lastPointSentAtMs`() {
        val runtime = recordingRuntime(trackerId = "tracker-1", lastPointSentAtMs = 5_000L)

        val resolved = TrackerLastReportedAtPolicy.resolve(
            trackerId = "tracker-1",
            runtime = runtime,
            resolverLastUpdatedMs = 9_000L,
        )

        assertEquals(5_000L, resolved)
    }

    @Test
    fun `local tracker with no successful upload yet returns null instead of stale resolver value`() {
        val runtime = recordingRuntime(trackerId = "tracker-1", lastPointSentAtMs = 0L)

        val resolved = TrackerLastReportedAtPolicy.resolve(
            trackerId = "tracker-1",
            runtime = runtime,
            resolverLastUpdatedMs = 9_000L,
        )

        assertNull(resolved)
    }

    @Test
    fun `non-local tracker passes resolver value through`() {
        val runtime = recordingRuntime(trackerId = "tracker-1", lastPointSentAtMs = 5_000L)

        val resolved = TrackerLastReportedAtPolicy.resolve(
            trackerId = "tracker-2",
            runtime = runtime,
            resolverLastUpdatedMs = 9_000L,
        )

        assertEquals(9_000L, resolved)
    }

    @Test
    fun `non-local tracker passes null resolver through`() {
        val runtime = recordingRuntime(trackerId = "tracker-1", lastPointSentAtMs = 5_000L)

        val resolved = TrackerLastReportedAtPolicy.resolve(
            trackerId = "tracker-2",
            runtime = runtime,
            resolverLastUpdatedMs = null,
        )

        assertNull(resolved)
    }

    @Test
    fun `blank tracker id passes resolver value through`() {
        val runtime = recordingRuntime(trackerId = "tracker-1", lastPointSentAtMs = 5_000L)

        val resolved = TrackerLastReportedAtPolicy.resolve(
            trackerId = "   ",
            runtime = runtime,
            resolverLastUpdatedMs = 9_000L,
        )

        assertEquals(9_000L, resolved)
    }

    @Test
    fun `tracker id is normalized via trim before comparing to local recording id`() {
        val runtime = recordingRuntime(trackerId = "tracker-1", lastPointSentAtMs = 5_000L)

        val resolved = TrackerLastReportedAtPolicy.resolve(
            trackerId = "  tracker-1  ",
            runtime = runtime,
            resolverLastUpdatedMs = 9_000L,
        )

        assertEquals(5_000L, resolved)
    }

    @Test
    fun `not currently recording locally falls back to resolver value`() {
        val runtime = TrackingRuntimeSnapshot(
            recordingRuntime = RecordingRuntime(
                sessionActive = false,
                startupActive = false,
                selectedTrackerId = "tracker-1",
            ),
            lastPointSentAtMs = 5_000L,
        )

        val resolved = TrackerLastReportedAtPolicy.resolve(
            trackerId = "tracker-1",
            runtime = runtime,
            resolverLastUpdatedMs = 9_000L,
        )

        assertEquals(9_000L, resolved)
    }

    private fun recordingRuntime(trackerId: String, lastPointSentAtMs: Long): TrackingRuntimeSnapshot {
        return TrackingRuntimeSnapshot(
            recordingRuntime = RecordingRuntime(
                sessionActive = true,
                selectedTrackerId = trackerId,
            ),
            lastPointSentAtMs = lastPointSentAtMs,
        )
    }
}
