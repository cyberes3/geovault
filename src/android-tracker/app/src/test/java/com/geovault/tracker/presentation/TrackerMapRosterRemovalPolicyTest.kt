package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapRosterRemovalPolicyTest {
    private fun queuedLocation(trackerId: String) = QueuedLocation(
        id = 0L,
        trackerId = trackerId,
        time = 1L,
        latitude = 1.0,
        longitude = 2.0,
        altitude = null,
        speed = null,
        bearing = null,
        accuracy = null,
        sat = null,
    )

    @Test
    fun noOpWhenRemovedTrackerIsUnrelatedToState() {
        val state = TrackerMapUiState(displayedTrackerId = "tracker1", displayedTrackerName = "Tracker 1")

        val outcome = TrackerMapRosterRemovalPolicy.applyRemoval(state, "tracker-unrelated")

        assertFalse(outcome.changed)
        assertEquals(state, outcome.nextState)
    }

    @Test
    fun clearsDisplayedStateAndSetsUnavailableNotice() {
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "tracker1",
            displayedTrackerName = "Alice",
            trail = listOf(queuedLocation("tracker1")),
        )

        val outcome = TrackerMapRosterRemovalPolicy.applyRemoval(state, "tracker1")

        assertTrue(outcome.changed)
        assertEquals("", outcome.nextState.displayedTrackerId)
        assertEquals("", outcome.nextState.displayedTrackerName)
        assertTrue(outcome.nextState.trail.isEmpty())
        assertEquals(
            TrackerMapUnavailableNotice(trackerId = "tracker1", trackerName = "Alice"),
            outcome.nextState.unavailableTrackerNotice,
        )
    }

    @Test
    fun preservesMultiModeTrailWhenADifferentTrackerIsDisplayed() {
        // GROUP/ALL_QUEUE display many trails at once via `allQueueTrailsByTracker`; only the
        // removed tracker's own entry should be dropped, not the single `trail` field (which is
        // not meaningfully tied to one tracker outside SINGLE_SESSION).
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            displayedTrackerId = "",
            trail = listOf(queuedLocation("tracker2")),
            allQueueTrailsByTracker = mapOf(
                "tracker1" to listOf(queuedLocation("tracker1")),
                "tracker2" to listOf(queuedLocation("tracker2")),
            ),
        )

        val outcome = TrackerMapRosterRemovalPolicy.applyRemoval(state, "tracker1")

        assertTrue(outcome.changed)
        assertEquals(listOf(queuedLocation("tracker2")), outcome.nextState.trail)
        assertEquals(setOf("tracker2"), outcome.nextState.allQueueTrailsByTracker.keys)
    }

    @Test
    fun clearsStreamingAndCachedRemoteState() {
        val state = TrackerMapUiState(
            streamTargetIds = setOf("tracker1", "tracker2"),
            activeStreamedTrackerIds = setOf("tracker1"),
            remoteLastPoints = mapOf("tracker1" to samplePoint()),
        )

        val outcome = TrackerMapRosterRemovalPolicy.applyRemoval(state, "tracker1")

        assertTrue(outcome.changed)
        assertTrue(outcome.shouldRefreshStreamTargets)
        assertEquals(setOf("tracker2"), outcome.nextState.streamTargetIds)
        assertTrue(outcome.nextState.activeStreamedTrackerIds.isEmpty())
        assertTrue(outcome.nextState.remoteLastPoints.isEmpty())
    }

    @Test
    fun clearsSelectionLockAndInfoCardWhenTiedToRemovedTracker() {
        val state = TrackerMapUiState(
            selectionLockTrackerId = "tracker1",
            followLockEnabled = false,
            selectedMapTracker = TrackerMapSelectionCard(
                trackerId = "tracker1",
                trackerName = "Alice",
                latitude = 1.0,
                longitude = 2.0,
                lastUpdatedMs = null,
                accuracyMeters = null,
                isOwned = false,
            ),
            isBottomCardVisible = true,
            streamTargetIds = setOf("tracker1"),
        )

        val outcome = TrackerMapRosterRemovalPolicy.applyRemoval(state, "tracker1")

        assertTrue(outcome.changed)
        assertEquals("", outcome.nextState.selectionLockTrackerId)
        assertNull(outcome.nextState.selectedMapTracker)
        assertFalse(outcome.nextState.isBottomCardVisible)
    }

    @Test
    fun blankRemovedIdIsNoOp() {
        val state = TrackerMapUiState(displayedTrackerId = "tracker1")

        val outcome = TrackerMapRosterRemovalPolicy.applyRemoval(state, "   ")

        assertFalse(outcome.changed)
        assertEquals(state, outcome.nextState)
    }

    private fun samplePoint() = com.geovault.tracker.policy.TrackPointEvent(
        trackId = "tracker1",
        lat = 1.0,
        lon = 2.0,
        timestampMs = 1L,
        source = com.geovault.tracker.policy.TrackPointSource.REMOTE_STREAM,
    )
}
