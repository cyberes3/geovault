package com.geovault.tracker.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailViewTest {

    @Test
    fun withoutTracker_dropsDegradedId() {
        val view = TrailView(
            degradedTrackerIds = setOf("t1", "t2"),
        )
        val next = view.withoutTracker("t1", clearSingleTrail = false)
        assertEquals(setOf("t2"), next.degradedTrackerIds)
        assertTrue(next.hasDegradedTrails)
        assertFalse(next.withoutTracker("t2", clearSingleTrail = false).hasDegradedTrails)
    }
}
