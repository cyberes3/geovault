package com.geovault.tracker

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackingLocationPolicyTest {

    @Test
    fun getProfileParams_biking_matchesConstants() {
        val triple = TrackingLocationPolicy.getProfileParams(1)
        assertEquals(TrackingLocationPolicy.BIKING_INTERVAL_SEC, triple.first)
        assertEquals(TrackingLocationPolicy.BIKING_DISTANCE_FILTER_METERS, triple.second, 0.001f)
        assertEquals(TrackingLocationPolicy.BIKING_ACCURACY_FILTER_METERS, triple.third, 0.001f)
    }

    @Test
    fun getRecommendedProfile_hysteresisFromWalking() {
        assertEquals(0, TrackingLocationPolicy.getRecommendedProfile(1.5f, 0))
        assertEquals(1, TrackingLocationPolicy.getRecommendedProfile(2.1f, 0))
    }

    @Test
    fun stationaryUpdate_threeClosePointsWithSignificantMotionOnly_triggersPause() {
        val filter = 10f
        val t0 = 1_000_000L
        val loc1 = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = t0
        }
        val loc2 = Location("test").apply {
            latitude = 0.0
            longitude = 0.00001
            time = t0 + 1000
        }
        val loc3 = Location("test").apply {
            latitude = 0.0
            longitude = 0.00002
            time = t0 + 2000
        }
        val (c1, p1) = TrackingLocationPolicy.stationaryUpdate(null, loc1, filter, 0, true)
        assertEquals(1, c1)
        assertFalse(p1)
        val (c2, p2) = TrackingLocationPolicy.stationaryUpdate(loc1, loc2, filter, c1, true)
        assertEquals(2, c2)
        assertFalse(p2)
        val (c3, p3) = TrackingLocationPolicy.stationaryUpdate(loc2, loc3, filter, c2, true)
        assertEquals(3, c3)
        assertTrue(p3)
    }

    @Test
    fun stationaryUpdate_speedAboveThreshold_resetsAndNoPause() {
        val filter = 50f
        val base = Location("test").apply {
            latitude = 1.0
            longitude = 1.0
            time = 5_000L
            speed = 2f
        }
        val (c, p) = TrackingLocationPolicy.stationaryUpdate(null, base, filter, 2, true)
        assertEquals(0, c)
        assertFalse(p)
    }

    @Test
    fun stationaryUpdate_activeMotionHint_resetsAndNoPause() {
        val base = Location("test").apply {
            latitude = 1.0
            longitude = 1.0
            time = 5_000L
        }
        val next = Location("test").apply {
            latitude = 1.0
            longitude = 1.00001
            time = 6_000L
        }

        val (count, shouldPause) = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = base,
            location = next,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            currentConsecutive = 2,
            significantMotionOnly = true,
            activeMotionHint = true,
        )

        assertEquals(0, count)
        assertFalse(shouldPause)
    }
}
