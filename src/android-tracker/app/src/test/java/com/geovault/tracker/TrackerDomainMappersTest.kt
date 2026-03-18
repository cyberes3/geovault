package com.geovault.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerDomainMappersTest {
    @Test
    fun lastUpdateMs_convertsSecondsToMillis() {
        val tracker = Tracker(
            id = "1",
            name = "A",
            color = null,
            last_point = listOf(10.0, 20.0, 1_700_000_000.0)
        )

        assertEquals(1_700_000_000_000L, tracker.lastUpdateMs())
    }

    @Test
    fun lastPosition_returnsLatLon() {
        val tracker = Tracker(
            id = "1",
            name = "A",
            color = null,
            last_point = listOf(10.0, 20.0, 1_700_000_000.0)
        )

        assertEquals(Pair(20.0, 10.0), tracker.lastPosition())
    }

    @Test
    fun lastPosition_returnsNullWhenNoPoint() {
        val tracker = Tracker(id = "1", name = "A", color = null, last_point = null)
        assertNull(tracker.lastPosition())
    }
}

