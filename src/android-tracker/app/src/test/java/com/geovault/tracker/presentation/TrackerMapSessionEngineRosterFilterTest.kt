package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roster filter contract for [TrackerMapSessionEngine.build]: a `null`
 * [TrackerMapSessionBuildInput.visibleTrackerIds] means "no filter" (used by
 * SINGLE_SESSION and tests that don't supply a roster). A non-null set is applied
 * verbatim, including the empty case, which means "render nothing" — exactly what we
 * need when the user has hidden every visible tracker.
 *
 * This is the architectural fix that makes hidden trackers, departed group members, and
 * deleted trackers stop rendering immediately without the caller needing to mutate the
 * VM's `_uiState.allQueueTrailsByTracker` map.
 */
class TrackerMapSessionEngineRosterFilterTest {

    @Test
    fun nullVisibleSet_appliesNoFilter() {
        val snapshot = TrackerMapSessionEngine.build(
            TrackerMapSessionBuildInput(
                state = TrackerMapUiState(mode = TrackerMapDisplayMode.ALL_QUEUE),
                plan = plan(),
                localRuntimeOverlayTrails = mapOf(
                    "a" to listOf(queued("a", 10L)),
                    "b" to listOf(queued("b", 20L)),
                ),
                visibleTrackerIds = null,
            )
        )
        assertEquals(setOf("a", "b"), snapshot.tracks.keys)
    }

    @Test
    fun emptyVisibleSet_rendersNothing() {
        // "User hid every visible tracker" — must NOT fall back to rendering everything.
        val snapshot = TrackerMapSessionEngine.build(
            TrackerMapSessionBuildInput(
                state = TrackerMapUiState(mode = TrackerMapDisplayMode.ALL_QUEUE),
                plan = plan(),
                localRuntimeOverlayTrails = mapOf(
                    "a" to listOf(queued("a", 10L)),
                    "b" to listOf(queued("b", 20L)),
                ),
                visibleTrackerIds = emptySet(),
            )
        )
        assertTrue(snapshot.tracks.isEmpty())
    }

    @Test
    fun nonEmpty_visibleSet_filtersOutMissingTrackers() {
        val snapshot = TrackerMapSessionEngine.build(
            TrackerMapSessionBuildInput(
                state = TrackerMapUiState(mode = TrackerMapDisplayMode.ALL_QUEUE),
                plan = plan(),
                localRuntimeOverlayTrails = mapOf(
                    "a" to listOf(queued("a", 10L)),
                    "b" to listOf(queued("b", 20L)),
                    "c" to listOf(queued("c", 30L)),
                ),
                visibleTrackerIds = setOf("a", "c"),
            )
        )
        assertEquals(setOf("a", "c"), snapshot.tracks.keys)
        assertFalse("b" in snapshot.tracks)
    }

    @Test
    fun visibleSet_thatExcludesAllTrails_yieldsEmptyTracks() {
        val snapshot = TrackerMapSessionEngine.build(
            TrackerMapSessionBuildInput(
                state = TrackerMapUiState(mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER),
                plan = plan(mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER),
                localRuntimeOverlayTrails = mapOf(
                    "departed-member" to listOf(queued("departed-member", 10L)),
                ),
                visibleTrackerIds = setOf("only-current-member"),
            )
        )
        assertTrue(snapshot.tracks.isEmpty())
    }

    private fun plan(
        mode: TrackerMapDisplayMode = TrackerMapDisplayMode.ALL_QUEUE,
    ): TrackerMapStreamingPlan {
        return TrackerMapStreamingPlan(
            mode = mode,
            selectedTrackerId = "",
            displayedTrackerId = "",
            displayedTrackerName = "",
            resolvedGroupId = "",
            groupTrackerIds = emptySet(),
            visibleRosterTrackerIds = emptySet(),
            locallyRecordedTrackerIds = emptySet(),
            remoteSubscriptionIds = emptySet(),
            acceptedRemoteTrackerIds = emptySet(),
            localOverlayTrackerIds = emptySet(),
            trailReloadPlan = TrackerMapTrailReloadPlan(source = TrackerMapTrailSource.MULTI_SERVER),
        )
    }

    private fun queued(trackerId: String, time: Long): QueuedLocation {
        return QueuedLocation(
            id = time,
            trackerId = trackerId,
            time = time,
            latitude = time.toDouble(),
            longitude = time.toDouble(),
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = "server_geometry",
            dist = null,
            startTimestampMs = null,
        )
    }
}
