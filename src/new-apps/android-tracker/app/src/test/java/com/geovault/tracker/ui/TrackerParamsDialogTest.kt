package com.geovault.tracker.ui

import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerParamsDialogTest {

    @Test
    fun toTrackerParamsUiModelOrNull_prefersLastPointTimestampAndNormalizesSeconds() {
        val tracker = Tracker(
            id = "t1",
            name = "Tracker",
            color = null,
            updated_at = 2_000_000_000_000L,
            last_point = listOf(10.0, 20.0, 1_700_000_000.0),
        )

        val model = tracker.toTrackerParamsUiModelOrNull()

        assertEquals(20.0, model?.latitude)
        assertEquals(10.0, model?.longitude)
        assertEquals(1_700_000_000_000L, model?.lastUpdatedMs)
    }
}
