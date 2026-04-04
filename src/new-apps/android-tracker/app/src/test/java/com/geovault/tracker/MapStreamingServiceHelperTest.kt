package com.geovault.tracker

import com.geovault.tracker.presentation.TrackerMapDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Test

class MapStreamingServiceHelperTest {

    @Test
    fun updateStreamingForDisplayedTracker_stopsWhenDisplayedMatchesSelected() {
        var starts = 0
        var stops = 0

        MapStreamingServiceHelper.updateStreamingForDisplayedTracker(
            displayedTrackerId = "tracker-a",
            displayedTrackerName = "Tracker A",
            selectedTrackerId = "tracker-a",
            mapMode = TrackerMapDisplayMode.SINGLE_SESSION,
            startStreaming = { _, _ -> starts += 1 },
            stopStreaming = { stops += 1 }
        )

        assertEquals(0, starts)
        assertEquals(1, stops)
    }

    @Test
    fun updateStreamingForDisplayedTracker_startsWhenDisplayedDiffersFromSelected() {
        var starts = 0
        var stops = 0
        var lastIds: Set<String>? = null

        MapStreamingServiceHelper.updateStreamingForDisplayedTracker(
            displayedTrackerId = "tracker-b",
            displayedTrackerName = "Tracker B",
            selectedTrackerId = "tracker-a",
            mapMode = TrackerMapDisplayMode.SINGLE_SESSION,
            startStreaming = { ids, _ ->
                starts += 1
                lastIds = ids
            },
            stopStreaming = { stops += 1 }
        )

        assertEquals(1, starts)
        assertEquals(setOf("tracker-b"), lastIds)
        assertEquals(0, stops)
    }

    @Test
    fun updateStreamingForDisplayedTracker_startsWhenDisplayedDiffersEvenIfTrackingRunning() {
        var starts = 0
        var stops = 0

        MapStreamingServiceHelper.updateStreamingForDisplayedTracker(
            displayedTrackerId = "tracker-b",
            displayedTrackerName = "Tracker B",
            selectedTrackerId = "tracker-a",
            mapMode = TrackerMapDisplayMode.SINGLE_SESSION,
            startStreaming = { _, _ -> starts += 1 },
            stopStreaming = { stops += 1 }
        )

        assertEquals(1, starts)
        assertEquals(0, stops)
    }

    @Test
    fun updateStreamingForDisplayedTracker_noopWhenDisplayedIsMissing() {
        var starts = 0
        var stops = 0

        MapStreamingServiceHelper.updateStreamingForDisplayedTracker(
            displayedTrackerId = null,
            displayedTrackerName = null,
            selectedTrackerId = "tracker-a",
            mapMode = TrackerMapDisplayMode.SINGLE_SESSION,
            startStreaming = { _, _ -> starts += 1 },
            stopStreaming = { stops += 1 }
        )

        assertEquals(0, starts)
        assertEquals(0, stops)
    }
}
