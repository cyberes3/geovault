package com.geovault.tracker.fragments.map

import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapAllTrackersFlowRestoreTest {
    @Test
    fun restoreAllTrackersFromCacheIfAvailable_prefersLastCoords() {
        val trackers = listOf(Tracker(id = "t1", name = "T1", color = null))
        val lastCoords = mapOf("t1" to listOf(listOf(1.0, 2.0), listOf(2.0, 3.0)))
        val fallbackCache = mutableMapOf<String, MutableList<List<Double>>>(
            "t1" to mutableListOf(listOf(9.0, 9.0), listOf(10.0, 10.0))
        )

        val restored = MapAllTrackersFlow.restoreAllTrackersFromCacheIfAvailable(trackers, lastCoords, fallbackCache)

        assertEquals(trackers, restored?.first)
        assertEquals(lastCoords, restored?.second)
    }

    @Test
    fun restoreAllTrackersFromCacheIfAvailable_returnsNullWhenNoTailData() {
        val trackers = listOf(Tracker(id = "t1", name = "T1", color = null))
        val restored = MapAllTrackersFlow.restoreAllTrackersFromCacheIfAvailable(
            lastTrackers = trackers,
            lastCoordsById = emptyMap(),
            multiTrackCoordsCache = emptyMap()
        )

        assertNull(restored)
    }
}

