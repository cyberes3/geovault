package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerMapFollowLockTargetTest {
    @Test
    fun followLockOn_usesPuckNotLiveHead() {
        val target = TrackerMapFollowLockTarget.resolve(
            followLockEnabled = true,
            puckLatitude = 12.0,
            puckLongitude = 34.0,
            liveHead = 1.0 to 2.0,
        )
        assertEquals(12.0 to 34.0, target)
    }

    @Test
    fun followLockOn_missingPuck_returnsNull() {
        val target = TrackerMapFollowLockTarget.resolve(
            followLockEnabled = true,
            puckLatitude = null,
            puckLongitude = 34.0,
            liveHead = 1.0 to 2.0,
        )
        assertNull(target)
    }

    @Test
    fun followLockOff_usesLiveHead() {
        val target = TrackerMapFollowLockTarget.resolve(
            followLockEnabled = false,
            puckLatitude = 12.0,
            puckLongitude = 34.0,
            liveHead = 1.0 to 2.0,
        )
        assertEquals(1.0 to 2.0, target)
    }
}
