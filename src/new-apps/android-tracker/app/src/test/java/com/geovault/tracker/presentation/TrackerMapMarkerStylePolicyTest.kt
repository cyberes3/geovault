package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerMapMarkerStylePolicyTest {

    @Test
    fun normalizedColorOrDefault_handlesMissingHashAndFallback() {
        assertEquals("#a1b2c3", TrackerMapMarkerStylePolicy.normalizedColorOrDefault("a1b2c3"))
        assertEquals(TrackerMapIconIds.DEFAULT_COLOR_HEX, TrackerMapMarkerStylePolicy.normalizedColorOrDefault(null))
    }

    @Test
    fun singleTrackerIconId_prefersDisplayedTrackerColor() {
        val iconId = TrackerMapMarkerStylePolicy.singleTrackerIconId(
            trackerColorById = mapOf("displayed" to "#AA33CC", "selected" to "#00FF00"),
            displayedTrackerId = "displayed",
            selectedTrackerId = "selected",
        )
        assertEquals(TrackerMapIconIds.selectedForColor("#AA33CC"), iconId)
    }

    @Test
    fun multiTrackerIconId_switchesSelectedVsSimple() {
        val selectedIcon = TrackerMapMarkerStylePolicy.multiTrackerIconId(
            trackerId = "t1",
            trackerColorById = mapOf("t1" to "#123456"),
            selectedMapTrackerId = "t1",
        )
        val simpleIcon = TrackerMapMarkerStylePolicy.multiTrackerIconId(
            trackerId = "t1",
            trackerColorById = mapOf("t1" to "#123456"),
            selectedMapTrackerId = "t2",
        )

        assertEquals(TrackerMapIconIds.selectedForColor("#123456"), selectedIcon)
        assertEquals(TrackerMapIconIds.simpleForColor("#123456"), simpleIcon)
    }
}
