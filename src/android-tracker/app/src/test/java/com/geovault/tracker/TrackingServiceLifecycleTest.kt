package com.geovault.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingServiceLifecycleTest {
    @Test
    fun hasValidSelectedTrackerId_acceptsUuid() {
        assertTrue(TrackingService.hasValidSelectedTrackerId("00000000-0000-0000-0000-000000000001"))
    }

    @Test
    fun hasValidSelectedTrackerId_rejectsInvalidValues() {
        assertFalse(TrackingService.hasValidSelectedTrackerId(""))
        assertFalse(TrackingService.hasValidSelectedTrackerId("abc"))
    }
}
