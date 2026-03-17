package com.geovault.tracker.fragments.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStandaloneLocationControllerTest {

    @Test
    fun shouldConsumePendingAutoZoom_trueOnlyWhenZoomAppliedAndNotSuppressed() {
        assertTrue(
            MapStandaloneLocationController.shouldConsumePendingAutoZoom(
                pendingAutoZoom = true,
                trackerFocusIntentActive = false,
                suppressStandaloneAutoZoom = false,
                zoomApplied = true
            )
        )
    }

    @Test
    fun shouldConsumePendingAutoZoom_falseWhenZoomNotApplied() {
        assertFalse(
            MapStandaloneLocationController.shouldConsumePendingAutoZoom(
                pendingAutoZoom = true,
                trackerFocusIntentActive = false,
                suppressStandaloneAutoZoom = false,
                zoomApplied = false
            )
        )
    }

    @Test
    fun shouldConsumePendingAutoZoom_falseWhenTrackerFocusOrSuppressed() {
        assertFalse(
            MapStandaloneLocationController.shouldConsumePendingAutoZoom(
                pendingAutoZoom = true,
                trackerFocusIntentActive = true,
                suppressStandaloneAutoZoom = false,
                zoomApplied = true
            )
        )
        assertFalse(
            MapStandaloneLocationController.shouldConsumePendingAutoZoom(
                pendingAutoZoom = true,
                trackerFocusIntentActive = false,
                suppressStandaloneAutoZoom = true,
                zoomApplied = true
            )
        )
    }
}
