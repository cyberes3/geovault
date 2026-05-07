package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerMapResumePolicyTest {

    private val resolver = TrackerMapResolveResumeUseCase()

    @Test
    fun longBackgroundGap_nonTrackingSingleWithTrail_doesNotReloadHistory() {
        val decision = resolver.resolve(
            TrackerMapResumeInput(
                trackingRunning = false,
                mapReady = true,
                showAllTrackers = false,
                mapViewContext = TrackerMapViewContext.SINGLE_TRACKER,
                activeStreamedTrackerIds = emptySet(),
                currentGroupTrackIds = emptySet(),
                selectedTrackerId = "a",
                displayedTrackerId = "a",
                hasTrailPoints = true,
                backgroundedDurationMs = 60_000L
            )
        )
        assertEquals(TrackerMapResumeDecision.RestartDisplayedTrackerStreaming, decision)
    }

    @Test
    fun trackingWithPointsSameTracker_returnsNoOp() {
        val decision = resolver.resolve(
            TrackerMapResumeInput(
                trackingRunning = true,
                mapReady = true,
                showAllTrackers = false,
                mapViewContext = TrackerMapViewContext.SINGLE_TRACKER,
                activeStreamedTrackerIds = emptySet(),
                currentGroupTrackIds = emptySet(),
                selectedTrackerId = "a",
                displayedTrackerId = "a",
                hasTrailPoints = true,
                backgroundedDurationMs = 1_000L
            )
        )
        assertEquals(TrackerMapResumeDecision.NoOp, decision)
    }

    @Test
    fun groupContext_prefersActiveStreams() {
        val decision = resolver.resolve(
            TrackerMapResumeInput(
                trackingRunning = false,
                mapReady = true,
                showAllTrackers = false,
                mapViewContext = TrackerMapViewContext.GROUP,
                activeStreamedTrackerIds = setOf("x", "y"),
                currentGroupTrackIds = setOf("z"),
                selectedTrackerId = "a",
                displayedTrackerId = "",
                hasTrailPoints = false,
                backgroundedDurationMs = 2_000L
            )
        )
        assertEquals(TrackerMapResumeDecision.StartMultiContextStreaming(setOf("x", "y")), decision)
    }

    @Test
    fun groupContext_includesSelectedTrackerOnRestart() {
        // GROUP STREAMING: when the user resumes into a group whose only member is their own
        // selected tracker, the resume policy should restart streaming for that tracker. In GROUP
        // mode the user explicitly chose a multi-tracker subscription that may include their own
        // tracker; previously the policy stripped the selected id out of every group resume,
        // producing MultiContextNoStreaming and silently dropping the user's own tracker from
        // the group stream on every resume tick.
        val decision = resolver.resolve(
            TrackerMapResumeInput(
                trackingRunning = false,
                mapReady = true,
                showAllTrackers = false,
                mapViewContext = TrackerMapViewContext.GROUP,
                activeStreamedTrackerIds = setOf("a"),
                currentGroupTrackIds = setOf("a"),
                selectedTrackerId = "a",
                displayedTrackerId = "",
                hasTrailPoints = false,
                backgroundedDurationMs = 2_000L
            )
        )
        assertEquals(TrackerMapResumeDecision.StartMultiContextStreaming(setOf("a")), decision)
    }

    @Test
    fun allQueueContext_includesSelectedTrackerInRestart() {
        // STREAMING EXCLUSION (resume): when the user is NOT recording, the selected tracker is
        // just another tracker. ALL_QUEUE on resume must NOT pre-strip it; the projector will
        // exclude only the locally-recorded tracker at the next reconcile if recording starts.
        val decision = resolver.resolve(
            TrackerMapResumeInput(
                trackingRunning = false,
                mapReady = true,
                showAllTrackers = true,
                mapViewContext = TrackerMapViewContext.SINGLE_TRACKER,
                activeStreamedTrackerIds = setOf("a", "b"),
                currentGroupTrackIds = emptySet(),
                selectedTrackerId = "a",
                displayedTrackerId = "",
                hasTrailPoints = false,
                backgroundedDurationMs = 2_000L
            )
        )
        assertEquals(TrackerMapResumeDecision.StartMultiContextStreaming(setOf("a", "b")), decision)
    }
}
