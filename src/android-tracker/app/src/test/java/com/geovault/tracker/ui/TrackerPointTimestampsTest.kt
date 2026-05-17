package com.geovault.tracker.ui

import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerPointTimestampsTest {

    @Test
    fun lastPointParamsMs_readsLatestTimestampLikeParam() {
        val tracker = Tracker(
            id = "t1",
            name = "Tracker",
            color = null,
            point_params = listOf(
                mapOf("starttimestamp" to 1_700_000_000L),
                mapOf("timestamp" to 1_700_000_123_000L),
            ),
        )

        assertEquals(1_700_000_123_000L, TrackerPointTimestamps.lastPointParamsMs(tracker))
    }

    @Test
    fun lastPointParamsMs_returnsNullWhenParamsHaveNoTimestampLikeValues() {
        val tracker = Tracker(
            id = "t1",
            name = "Tracker",
            color = null,
            point_params = listOf(mapOf("battery" to 95)),
        )

        assertNull(TrackerPointTimestamps.lastPointParamsMs(tracker))
    }
}
