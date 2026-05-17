package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapRenderMetadataPolicyTest {

    @Test
    fun capture_fingerprintIncludesRecentDataWindow() {
        val trackers = listOf(
            tracker(id = "a", name = "A", window = "1h"),
            tracker(id = "b", name = "B", window = "all"),
        )
        val snapshot = TrackerMapRenderMetadataPolicy.capture(
            trackers = trackers,
            groups = emptyList(),
            mapVisibility = null,
        )
        assertTrue(snapshot.fingerprint.contains("a:A:#00ff00:1h"))
        assertTrue(snapshot.fingerprint.contains("b:B:#00ff00:all"))
        assertEquals("1h", snapshot.recentDataWindowByTracker["a"])
        assertEquals("all", snapshot.recentDataWindowByTracker["b"])
    }

    @Test
    fun diff_detectsRecentDataWindowChangePerTracker() {
        val before = TrackerMapRenderMetadataPolicy.capture(
            trackers = listOf(tracker(id = "t1", name = "T", window = "1h")),
            groups = emptyList(),
            mapVisibility = null,
        )
        val after = TrackerMapRenderMetadataPolicy.capture(
            trackers = listOf(tracker(id = "t1", name = "T", window = "session")),
            groups = emptyList(),
            mapVisibility = null,
        )
        val diff = TrackerMapRenderMetadataPolicy.diff(before, after)
        assertTrue(diff.fingerprintChanged)
        assertEquals(setOf("t1"), diff.recentDataWindowChangedTrackerIds)
    }

    @Test
    fun diff_cosmeticOnlyChange_hasNoWindowChanges() {
        val before = TrackerMapRenderMetadataPolicy.capture(
            trackers = listOf(tracker(id = "t1", name = "Old", window = "1h")),
            groups = emptyList(),
            mapVisibility = null,
        )
        val after = TrackerMapRenderMetadataPolicy.capture(
            trackers = listOf(tracker(id = "t1", name = "New", window = "1h")),
            groups = emptyList(),
            mapVisibility = null,
        )
        val diff = TrackerMapRenderMetadataPolicy.diff(before, after)
        assertTrue(diff.fingerprintChanged)
        assertTrue(diff.recentDataWindowChangedTrackerIds.isEmpty())
    }

    @Test
    fun diff_firstSnapshot_hasNoWindowChanges() {
        val next = TrackerMapRenderMetadataPolicy.capture(
            trackers = listOf(tracker(id = "t1", window = "1h")),
            groups = emptyList(),
            mapVisibility = null,
        )
        val diff = TrackerMapRenderMetadataPolicy.diff(previous = null, next = next)
        assertTrue(diff.fingerprintChanged)
        assertFalse(diff.recentDataWindowChangedTrackerIds.isNotEmpty())
    }

    private fun tracker(
        id: String,
        name: String = "Tracker",
        window: String? = null,
    ): Tracker {
        val settings = window?.let { mapOf("recent_data_window" to it) }
        return Tracker(
            id = id,
            name = name,
            color = "#00ff00",
            settings = settings,
        )
    }
}
