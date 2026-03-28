package com.geovault.tracker.fragments.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSingleTrackerRefreshPolicyTest {
    @Test
    fun resolve_skipsStandardRefreshWhileTrackingWithPoints() {
        val decision = MapSingleTrackerRefreshPolicy.resolve(
            MapSingleTrackerRefreshInput(
                trackingActive = true,
                hasTrackPoints = true,
                forceReplace = false,
                trigger = MapSingleTrackerRefreshTrigger.STANDARD
            )
        )

        assertFalse(decision.shouldFetch)
        assertFalse(decision.shouldPrimeSessionAnchorResync)
    }

    @Test
    fun resolve_allowsForceReplaceForSettingsAndPrimesResync() {
        val decision = MapSingleTrackerRefreshPolicy.resolve(
            MapSingleTrackerRefreshInput(
                trackingActive = true,
                hasTrackPoints = true,
                forceReplace = true,
                trigger = MapSingleTrackerRefreshTrigger.SETTINGS_CHANGE
            )
        )

        assertTrue(decision.shouldFetch)
        assertTrue(decision.shouldPrimeSessionAnchorResync)
    }

    @Test
    fun resolve_allowsForceReplaceForHistoryClearAndPrimesResync() {
        val decision = MapSingleTrackerRefreshPolicy.resolve(
            MapSingleTrackerRefreshInput(
                trackingActive = true,
                hasTrackPoints = true,
                forceReplace = true,
                trigger = MapSingleTrackerRefreshTrigger.HISTORY_CLEAR
            )
        )

        assertTrue(decision.shouldFetch)
        assertTrue(decision.shouldPrimeSessionAnchorResync)
    }

    @Test
    fun resolve_allowsStandardRefreshWhenTrackIsEmpty() {
        val decision = MapSingleTrackerRefreshPolicy.resolve(
            MapSingleTrackerRefreshInput(
                trackingActive = true,
                hasTrackPoints = false,
                forceReplace = false,
                trigger = MapSingleTrackerRefreshTrigger.STANDARD
            )
        )

        assertTrue(decision.shouldFetch)
        assertFalse(decision.shouldPrimeSessionAnchorResync)
    }
}
