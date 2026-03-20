package com.geovault.tracker.fragments.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSingleTrackerHistoryPolicyTest {
    @Test
    fun decide_clearsWhenForceReplaceAndNoCoordinates() {
        val decision = MapSingleTrackerHistoryPolicy.decide(
            SingleTrackerHistoryApplyInput(
                forceReplace = true,
                normalizedCoordCount = 0,
                hasTrackPoints = true,
                trackingActive = false,
                isExternalStreaming = false,
                isSelectedDefaultTrackerMode = false
            )
        )

        assertTrue(decision.shouldClearForEmptyForceReplace)
        assertFalse(decision.shouldApplyGeometry)
    }

    @Test
    fun decide_appliesSinglePointWhenForceReplace() {
        val decision = MapSingleTrackerHistoryPolicy.decide(
            SingleTrackerHistoryApplyInput(
                forceReplace = true,
                normalizedCoordCount = 1,
                hasTrackPoints = true,
                trackingActive = false,
                isExternalStreaming = false,
                isSelectedDefaultTrackerMode = false
            )
        )

        assertFalse(decision.shouldClearForEmptyForceReplace)
        assertTrue(decision.shouldApplyGeometry)
    }

    @Test
    fun decide_appliesSinglePointWhenTrackIsEmpty() {
        val decision = MapSingleTrackerHistoryPolicy.decide(
            SingleTrackerHistoryApplyInput(
                forceReplace = false,
                normalizedCoordCount = 1,
                hasTrackPoints = false,
                trackingActive = false,
                isExternalStreaming = false,
                isSelectedDefaultTrackerMode = false
            )
        )

        assertFalse(decision.shouldClearForEmptyForceReplace)
        assertTrue(decision.shouldApplyGeometry)
    }

    @Test
    fun decide_skipsApplyWhenNotForceReplaceAndTrackAlreadyHasData() {
        val decision = MapSingleTrackerHistoryPolicy.decide(
            SingleTrackerHistoryApplyInput(
                forceReplace = false,
                normalizedCoordCount = 2,
                hasTrackPoints = true,
                trackingActive = false,
                isExternalStreaming = false,
                isSelectedDefaultTrackerMode = false
            )
        )

        assertFalse(decision.shouldClearForEmptyForceReplace)
        assertFalse(decision.shouldApplyGeometry)
    }

    @Test
    fun decide_appliesForExternalStreamingWhenTrackingActiveInDefaultMode() {
        val decision = MapSingleTrackerHistoryPolicy.decide(
            SingleTrackerHistoryApplyInput(
                forceReplace = false,
                normalizedCoordCount = 1,
                hasTrackPoints = true,
                trackingActive = true,
                isExternalStreaming = true,
                isSelectedDefaultTrackerMode = true
            )
        )

        assertFalse(decision.shouldClearForEmptyForceReplace)
        assertTrue(decision.shouldApplyGeometry)
    }
}
