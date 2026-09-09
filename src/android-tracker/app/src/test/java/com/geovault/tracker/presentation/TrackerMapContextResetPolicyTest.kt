package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.map.MapSessionEngine
import com.geovault.tracker.map.MapTrailEngine
import com.geovault.tracker.map.TrailView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapContextResetPolicyTest {

    @Test
    fun reset_restoreSelectedFromGroup_preservesOnlySelectedTrailAsSingleTrail() {
        val selectedTrail = listOf(point("selected", 1L), point("selected", 2L))
        val remoteTrail = listOf(point("remote", 3L))
        val reset = MapSessionEngine.resetMapContext(
            state = TrackerMapUiState(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            ),
            preservedSingleTrackerId = "selected",
            trails = TrailView(
                singleTrail = remoteTrail,
                tracksByTrackerId = mapOf(
                    "selected" to selectedTrail,
                    "remote" to remoteTrail,
                ),
            ),
        )

        assertEquals(selectedTrail, reset.nextTrails.singleTrail)
        assertTrue(reset.nextTrails.tracksByTrackerId.isEmpty())
        assertTrue(reset.nextTrails.remoteLastPoints.isEmpty())
    }

    @Test
    fun reset_withoutPreservedTracker_clearsRenderedTrailData() {
        val reset = MapSessionEngine.resetMapContext(
            state = TrackerMapUiState(),
            trails = TrailView(
                singleTrail = listOf(point("selected", 1L)),
                tracksByTrackerId = mapOf("selected" to listOf(point("selected", 1L))),
            ),
        )

        assertTrue(reset.nextTrails.singleTrail.isEmpty())
        assertTrue(reset.nextTrails.tracksByTrackerId.isEmpty())
        assertTrue(reset.nextTrails.remoteLastPoints.isEmpty())
    }

    @Test
    fun reset_restoreSelectedFromSingleMode_usesExistingSingleTrailWhenDisplayedMatches() {
        val selectedTrail = listOf(point("selected", 1L), point("selected", 2L))
        val reset = MapSessionEngine.resetMapContext(
            state = TrackerMapUiState(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayedTrackerId = "selected",
            ),
            preservedSingleTrackerId = "selected",
            trails = TrailView(singleTrail = selectedTrail),
        )

        assertEquals(selectedTrail, reset.nextTrails.singleTrail)
    }

    private fun point(trackerId: String, time: Long): QueuedLocation {
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
            prov = MapTrailEngine.PROVENANCE_SERVER_GEOMETRY,
            dist = null,
        )
    }
}
