package com.geovault.tracker.fragments.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStreamingServiceHelperTest {

    @Test
    fun updateStreamingForDisplayedTracker_stopsInGroupContext() {
        var stopped = false
        var started = false
        MapStreamingServiceHelper.updateStreamingForDisplayedTracker(
            displayedTrackerId = "tracker-1",
            displayedTrackerName = "Tracker 1",
            selectedTrackerId = "tracker-selected",
            mapViewContext = MapViewContext.GROUP,
            startStreaming = { _, _ -> started = true },
            stopStreaming = { stopped = true }
        )
        assertTrue(stopped)
        assertTrue(!started)
    }

    @Test
    fun updateStreamingForDisplayedTracker_startsInSingleTrackerContext() {
        var stopped = false
        var startedIds: Set<String>? = null
        var startedName: String? = null
        MapStreamingServiceHelper.updateStreamingForDisplayedTracker(
            displayedTrackerId = "tracker-1",
            displayedTrackerName = "Tracker 1",
            selectedTrackerId = "tracker-selected",
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            startStreaming = { ids, name ->
                startedIds = ids
                startedName = name
            },
            stopStreaming = { stopped = true }
        )
        assertTrue(!stopped)
        assertEquals(setOf("tracker-1"), startedIds)
        assertEquals("Tracker 1", startedName)
    }

    @Test
    fun updateStreamingForDisplayedTracker_noopWhenSingleContextWithoutId() {
        var stopped = false
        var started = false
        MapStreamingServiceHelper.updateStreamingForDisplayedTracker(
            displayedTrackerId = null,
            displayedTrackerName = "Tracker 1",
            selectedTrackerId = "tracker-selected",
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            startStreaming = { _, _ -> started = true },
            stopStreaming = { stopped = true }
        )
        assertTrue(!stopped)
        assertTrue(!started)
    }

    @Test
    fun updateStreamingForDisplayedTracker_stopsWhenDisplayedIsSelectedDefault() {
        var stopped = false
        var started = false
        MapStreamingServiceHelper.updateStreamingForDisplayedTracker(
            displayedTrackerId = "tracker-1",
            displayedTrackerName = "Tracker 1",
            selectedTrackerId = "tracker-1",
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            startStreaming = { _, _ -> started = true },
            stopStreaming = { stopped = true }
        )
        assertTrue(stopped)
        assertTrue(!started)
    }
}
