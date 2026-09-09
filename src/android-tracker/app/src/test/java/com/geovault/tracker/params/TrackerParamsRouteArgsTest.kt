package com.geovault.tracker.params

import com.geovault.tracker.Tracker
import com.geovault.tracker.pointParamsOf
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

    @Test
    fun paramsRouteArgs_usesCatalogParamsAndLiveFix() {
        val tracker = Tracker(
            id = "t1",
            name = "Tracker",
            color = null,
            last_point = listOf(1.0, 2.0, 0.0),
            point_params = listOf(pointParamsOf("battery" to 80)),
        )

        val args = paramsRouteArgs(
            tracker = tracker,
            trackerId = "t1",
            displayName = "Live",
            lastUpdateMs = 4_000L,
            latitude = 3.0,
            longitude = 4.0,
            isOwner = true,
        )

        assertEquals("Live", args.seed.displayName)
        assertEquals(3.0, args.seed.latitude!!, 0.0)
        assertEquals(4.0, args.seed.longitude!!, 0.0)
        assertEquals(4_000L, args.seed.lastUpdateMs)
        assertEquals(80L, args.seed.initialParams?.get("battery"))
    }
}
