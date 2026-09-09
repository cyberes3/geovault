package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.map.MapSessionEngine
import com.geovault.tracker.map.TrailView
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

        val outcome = MapSessionEngine.applyRosterRemoval(state, "tracker-unrelated")

        assertFalse(outcome.changed)
        assertEquals(state, outcome.nextState)
    }

    @Test
    fun clearsDisplayedStateAndSetsUnavailableNotice() {
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "tracker1",
            displayedTrackerName = "Alice",
        )

        val outcome = MapSessionEngine.applyRosterRemoval(
            state = state,
            removedTrackerId = "tracker1",
            trails = TrailView(singleTrail = listOf(queuedLocation("tracker1"))),
        )

        assertTrue(outcome.changed)
        assertEquals("", outcome.nextState.displayedTrackerId)
        assertEquals("", outcome.nextState.displayedTrackerName)
        assertTrue(outcome.nextTrails.singleTrail.isEmpty())
        assertEquals(
            TrackerMapUnavailableNotice(trackerId = "tracker1", trackerName = "Alice"),
            outcome.nextState.unavailableTrackerNotice,
        )
    }

    @Test
    fun preservesMultiModeTrailWhenADifferentTrackerIsDisplayed() {
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            displayedTrackerId = "",
        )

        val outcome = MapSessionEngine.applyRosterRemoval(
            state = state,
            removedTrackerId = "tracker1",
            trails = TrailView(
                singleTrail = listOf(queuedLocation("tracker2")),
                tracksByTrackerId = mapOf(
                    "tracker1" to listOf(queuedLocation("tracker1")),
                    "tracker2" to listOf(queuedLocation("tracker2")),
                ),
            ),
        )

        assertTrue(outcome.changed)
        assertEquals(listOf(queuedLocation("tracker2")), outcome.nextTrails.singleTrail)
        assertEquals(setOf("tracker2"), outcome.nextTrails.tracksByTrackerId.keys)
    }

    @Test
    fun clearsStreamingAndCachedRemoteState() {
        val state = TrackerMapUiState(
            activeStreamedTrackerIds = setOf("tracker1"),
        )

        val outcome = MapSessionEngine.applyRosterRemoval(
            state = state,
            removedTrackerId = "tracker1",
            mapLeaseIds = setOf("tracker1", "tracker2"),
            trails = TrailView(remoteLastPoints = mapOf("tracker1" to samplePoint())),
        )

        assertTrue(outcome.changed)
        assertTrue(outcome.shouldRefreshStreamTargets)
        assertTrue(outcome.nextState.activeStreamedTrackerIds.isEmpty())
        assertTrue(outcome.nextTrails.remoteLastPoints.isEmpty())
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
        )

        val outcome = MapSessionEngine.applyRosterRemoval(state, "tracker1")

        assertTrue(outcome.changed)
        assertEquals("", outcome.nextState.selectionLockTrackerId)
        assertNull(outcome.nextState.selectedMapTracker)
        assertFalse(outcome.nextState.isBottomCardVisible)
    }

    @Test
    fun blankRemovedIdIsNoOp() {
        val state = TrackerMapUiState(displayedTrackerId = "tracker1")

        val outcome = MapSessionEngine.applyRosterRemoval(state, "   ")

        assertFalse(outcome.changed)
        assertEquals(state, outcome.nextState)
    }

    private fun samplePoint() = com.geovault.tracker.domain.TrackPoint(
        trackerId = "tracker1",
        latitude = 1.0,
        longitude = 2.0,
        timeMs = 1L,
        provenance = com.geovault.tracker.policy.TrackPointSource.REMOTE_STREAM,
    )
}
