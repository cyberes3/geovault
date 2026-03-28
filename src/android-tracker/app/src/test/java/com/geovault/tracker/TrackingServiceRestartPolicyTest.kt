package com.geovault.tracker

import org.junit.Assert.assertFalse
import org.junit.Test

class TrackingServiceRestartPolicyTest {

    @Test
    fun shouldRestart_whenWasTracking_returnsFalse() {
        assertFalse(
            TrackingService.shouldRestartTrackingAfterProcessDeath()
        )
    }

    @Test
    fun shouldRestart_whenNotTracking_returnsFalse() {
        assertFalse(
            TrackingService.shouldRestartTrackingAfterProcessDeath()
        )
    }
}
