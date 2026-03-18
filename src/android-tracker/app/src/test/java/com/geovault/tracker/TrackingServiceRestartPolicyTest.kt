package com.geovault.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingServiceRestartPolicyTest {

    @Test
    fun shouldRestart_whenWasTrackingAndRestartEnabled_returnsTrue() {
        assertTrue(
            TrackingService.shouldRestartTrackingAfterProcessDeath(
                wasTrackingBeforeExit = true,
                restartTrackingIfKilled = true
            )
        )
    }

    @Test
    fun shouldRestart_whenWasTrackingButRestartDisabled_returnsFalse() {
        assertFalse(
            TrackingService.shouldRestartTrackingAfterProcessDeath(
                wasTrackingBeforeExit = true,
                restartTrackingIfKilled = false
            )
        )
    }

    @Test
    fun shouldRestart_whenNotTrackingButRestartEnabled_returnsFalse() {
        assertFalse(
            TrackingService.shouldRestartTrackingAfterProcessDeath(
                wasTrackingBeforeExit = false,
                restartTrackingIfKilled = true
            )
        )
    }

    @Test
    fun shouldRestart_whenNotTrackingAndRestartDisabled_returnsFalse() {
        assertFalse(
            TrackingService.shouldRestartTrackingAfterProcessDeath(
                wasTrackingBeforeExit = false,
                restartTrackingIfKilled = false
            )
        )
    }
}
