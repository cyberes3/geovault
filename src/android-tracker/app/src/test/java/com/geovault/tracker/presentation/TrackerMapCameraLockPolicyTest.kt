package com.geovault.tracker.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapCameraLockPolicyTest {

    @Test
    fun shouldRenderUserLocation_disabledWhileTrackingRunning() {
        assertFalse(TrackerMapCameraLockPolicy.shouldRenderUserLocation(runtimeRunning = true))
        assertTrue(TrackerMapCameraLockPolicy.shouldRenderUserLocation(runtimeRunning = false))
    }
}
