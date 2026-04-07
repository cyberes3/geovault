package com.geovault.tracker.params

import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerParamsRouteArgsTest {

    @Test
    fun toTrackerParamsRouteArgs_prefersLastPointTimestampAndNormalizesSeconds() {
        val tracker = Tracker(
            id = "t1",
            name = "Tracker",
            color = null,
            updated_at = 2_000_000_000_000L,
            last_point = listOf(10.0, 20.0, 1_700_000_000.0),
        )

        val args = tracker.toTrackerParamsRouteArgs()

        assertEquals("t1", args.trackerId)
        assertEquals(20.0, args.seed.latitude!!, 0.0)
        assertEquals(10.0, args.seed.longitude!!, 0.0)
        assertEquals(1_700_000_000_000L, args.seed.lastUpdateMs)
    }
}
