package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerMapLastPointResolverTest {

    @Test
    fun resolve_usesRosterLastPointAndUpdatedAt() {
        val updated = 1_800_000_000_000L
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "t1",
            displayedTrackerName = "T1",
            runtime = TrackingRuntimeSnapshot(
                selectedTrackerId = "other",
            ),
        )
        val t = Tracker(
            id = "t1",
            name = "T1",
            color = null,
            last_point = listOf(-10.0, 20.0, 0.0),
            updated_at = updated,
        )
        val p = TrackerMapLastPointResolver.resolve(state, "t1", t)
        assertNotNull(p)
        assertEquals(-10.0, p!!.longitude, 0.0)
        assertEquals(20.0, p.latitude, 0.0)
        assertEquals(updated, p.lastUpdatedMs)
    }

    @Test
    fun resolve_emptyTrackerId_returnsNull() {
        val p = TrackerMapLastPointResolver.resolve(TrackerMapUiState(), "  ", null)
        assertNull(p)
    }
}
