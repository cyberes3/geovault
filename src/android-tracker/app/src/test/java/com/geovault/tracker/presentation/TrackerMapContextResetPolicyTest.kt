package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapContextResetPolicyTest {

    @Test
    fun reset_restoreSelectedFromGroup_preservesOnlySelectedTrailAsSingleTrail() {
        val selectedTrail = listOf(point("selected", 1L), point("selected", 2L))
        val remoteTrail = listOf(point("remote", 3L))
        val reset = TrackerMapContextResetPolicy.reset(
            TrackerMapContextResetInput(
                state = TrackerMapUiState(
                    mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                    trail = remoteTrail,
                    allQueueTrailsByTracker = mapOf(
                        "selected" to selectedTrail,
                        "remote" to remoteTrail,
                    ),
                ),
                preservedSingleTrackerId = "selected",
            )
        )

        assertEquals(selectedTrail, reset.trail)
        assertTrue(reset.allQueueTrailsByTracker.isEmpty())
        assertTrue(reset.remoteLastPoints.isEmpty())
    }

    @Test
    fun reset_withoutPreservedTracker_clearsRenderedTrailData() {
        val reset = TrackerMapContextResetPolicy.reset(
            TrackerMapContextResetInput(
                state = TrackerMapUiState(
                    trail = listOf(point("selected", 1L)),
                    allQueueTrailsByTracker = mapOf("selected" to listOf(point("selected", 1L))),
                )
            )
        )

        assertTrue(reset.trail.isEmpty())
        assertTrue(reset.allQueueTrailsByTracker.isEmpty())
        assertTrue(reset.remoteLastPoints.isEmpty())
    }

    @Test
    fun reset_restoreSelectedFromSingleMode_usesExistingSingleTrailWhenDisplayedMatches() {
        val selectedTrail = listOf(point("selected", 1L), point("selected", 2L))
        val reset = TrackerMapContextResetPolicy.reset(
            TrackerMapContextResetInput(
                state = TrackerMapUiState(
                    mode = TrackerMapDisplayMode.SINGLE_SESSION,
                    displayedTrackerId = "selected",
                    trail = selectedTrail,
                ),
                preservedSingleTrackerId = "selected",
            )
        )

        assertEquals(selectedTrail, reset.trail)
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
            prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY,
            dist = null,
        )
    }
}
