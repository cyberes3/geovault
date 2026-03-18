package com.geovault.tracker.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingSettingsReapplyLogicTest {

    @Test
    fun shouldReapply_whenIntervalChanges_returnsTrue() {
        val previous = TrackerSettings(loggingIntervalSec = 15L)
        val current = previous.copy(loggingIntervalSec = 30L)
        assertTrue(TrackingSettingsReapplyPolicy.shouldReapplyLocationRequest(previous, current))
    }

    @Test
    fun shouldReapply_whenDistanceChanges_returnsTrue() {
        val previous = TrackerSettings(distanceFilterMeters = 10f)
        val current = previous.copy(distanceFilterMeters = 25f)
        assertTrue(TrackingSettingsReapplyPolicy.shouldReapplyLocationRequest(previous, current))
    }

    @Test
    fun shouldReapply_whenAutoModeChanges_returnsTrue() {
        val previous = TrackerSettings(autoTrackingMode = false)
        val current = previous.copy(autoTrackingMode = true)
        assertTrue(TrackingSettingsReapplyPolicy.shouldReapplyLocationRequest(previous, current))
    }

    @Test
    fun shouldReapply_whenProfileChanges_returnsTrue() {
        val previous = TrackerSettings(trackingProfile = TrackerTrackingProfile.WALKING)
        val current = previous.copy(trackingProfile = TrackerTrackingProfile.DRIVING)
        assertTrue(TrackingSettingsReapplyPolicy.shouldReapplyLocationRequest(previous, current))
    }

    @Test
    fun shouldReapply_whenNonLocationFieldsChange_returnsFalse() {
        val previous = TrackerSettings(sendExtendedData = true, significantDataOnly = true)
        val current = previous.copy(sendExtendedData = false, significantDataOnly = false)
        assertFalse(TrackingSettingsReapplyPolicy.shouldReapplyLocationRequest(previous, current))
    }
}
