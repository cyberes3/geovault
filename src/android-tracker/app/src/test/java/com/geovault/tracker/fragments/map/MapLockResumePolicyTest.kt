package com.geovault.tracker.fragments.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLockResumePolicyTest {
    @Test
    fun decide_reappliesFollowLock_whenEnabledAndTargetPresent() {
        val decision = MapLockResumePolicy.decide(
            LockResumePolicyInput(
                followLockEnabled = true,
                hasLockTarget = true,
                hasTrackPoint = false,
                showMyLocationEnabled = false,
                gpsLocationLockActive = false,
                liveActiveFitEnabled = false,
                liveActiveFitAvailable = false,
                trackerOrLiveLockActive = true
            )
        )

        assertTrue(decision.shouldRecenterFollowLock)
        assertFalse(decision.shouldRecenterGpsLock)
        assertFalse(decision.shouldReapplyLiveLock)
    }

    @Test
    fun decide_reappliesGpsLock_whenMyLocationAndGpsLockActive() {
        val decision = MapLockResumePolicy.decide(
            LockResumePolicyInput(
                followLockEnabled = false,
                hasLockTarget = false,
                hasTrackPoint = false,
                showMyLocationEnabled = true,
                gpsLocationLockActive = true,
                liveActiveFitEnabled = false,
                liveActiveFitAvailable = false,
                trackerOrLiveLockActive = false
            )
        )

        assertFalse(decision.shouldRecenterFollowLock)
        assertTrue(decision.shouldRecenterGpsLock)
        assertFalse(decision.shouldReapplyLiveLock)
    }

    @Test
    fun decide_reappliesLiveLock_whenEnabledAndAvailable() {
        val decision = MapLockResumePolicy.decide(
            LockResumePolicyInput(
                followLockEnabled = false,
                hasLockTarget = false,
                hasTrackPoint = false,
                showMyLocationEnabled = false,
                gpsLocationLockActive = false,
                liveActiveFitEnabled = true,
                liveActiveFitAvailable = true,
                trackerOrLiveLockActive = true
            )
        )

        assertFalse(decision.shouldRecenterFollowLock)
        assertFalse(decision.shouldRecenterGpsLock)
        assertTrue(decision.shouldReapplyLiveLock)
    }

    @Test
    fun decide_doesNotRecenterGps_whenTrackerOrLiveLockIsActive() {
        val decision = MapLockResumePolicy.decide(
            LockResumePolicyInput(
                followLockEnabled = false,
                hasLockTarget = false,
                hasTrackPoint = false,
                showMyLocationEnabled = true,
                gpsLocationLockActive = true,
                liveActiveFitEnabled = true,
                liveActiveFitAvailable = true,
                trackerOrLiveLockActive = true
            )
        )

        assertFalse(decision.shouldRecenterGpsLock)
    }

    @Test
    fun decide_doesNotReapplyLive_whenFollowLockEnabled() {
        val decision = MapLockResumePolicy.decide(
            LockResumePolicyInput(
                followLockEnabled = true,
                hasLockTarget = true,
                hasTrackPoint = true,
                showMyLocationEnabled = false,
                gpsLocationLockActive = false,
                liveActiveFitEnabled = true,
                liveActiveFitAvailable = true,
                trackerOrLiveLockActive = true
            )
        )

        assertFalse(decision.shouldReapplyLiveLock)
    }
}
