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
        val params = TrackingLocationPolicy.getProfileParams(1)
        assertEquals(TrackingLocationPolicy.BIKING_INTERVAL_SEC, params.first)
        assertEquals(TrackingLocationPolicy.BIKING_DISTANCE_FILTER_METERS, params.second, 0.001f)
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
        val r1 = TrackingLocationPolicy.stationaryUpdate(null, loc1, filter, 0, true)
        assertEquals(1, r1.consecutive)
        assertFalse(r1.shouldPause)
        val r2 = TrackingLocationPolicy.stationaryUpdate(loc1, loc2, filter, r1.consecutive, true)
        assertEquals(2, r2.consecutive)
        assertFalse(r2.shouldPause)
        val r3 = TrackingLocationPolicy.stationaryUpdate(loc2, loc3, filter, r2.consecutive, true)
        assertEquals(3, r3.consecutive)
        assertTrue(r3.shouldPause)
    }

    @Test
    fun stationaryUpdate_phantomSpeedWithoutDisplacement_doesNotResetCounter() {
        // Multipath bursts indoors produce phantom Location.speed
        // readings while the user sits still. Geometry must corroborate:
        // a 5 m/s "speed" with the device parked at the anchor is NOT
        // motion evidence and must not reset the stationary counter.
        val filter = 50f
        val anchor = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 0L
            accuracy = 8f
        }
        val phantomMotion = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 5_000L
            accuracy = 8f
            speed = 5f
        }
        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor,
            location = phantomMotion,
            stationaryRadiusMeters = filter,
            currentConsecutive = 2,
            significantMotionOnly = true,
        )
        assertTrue(
            "phantom GPS speed without geometric displacement must not reset stationary counter",
            result.consecutive >= 2
        )
    }

    @Test
    fun stationaryUpdate_realMotion_resetsCounter() {
        // Real motion: speed > floor AND displacement past the radius.
        val filter = 25f
        val anchor = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 0L
            accuracy = 6f
        }
        val moved = Location("test").apply {
            // ~110 m east of anchor at the equator
            latitude = 0.0
            longitude = 0.001
            time = 30_000L
            accuracy = 6f
            speed = 4f
        }
        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor,
            location = moved,
            stationaryRadiusMeters = filter,
            currentConsecutive = 2,
            significantMotionOnly = true,
        )
        assertEquals(0, result.consecutive)
        assertFalse(result.shouldPause)
        assertEquals("gps_motion_corroborated", result.reason)
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

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = base,
            location = next,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            currentConsecutive = 2,
            significantMotionOnly = true,
            activeMotionHint = true,
        )

        assertEquals(0, result.consecutive)
        assertFalse(result.shouldPause)
    }

    /**
     * Regression: this is the exact bug observed in the field at 21:14:55.
     * Three fixes with mediocre 27 m accuracy that the upstream filter
     * snapped to the anchor (`uncertainty-suppressed`) used to advance the
     * stationary counter to 3 and pause GPS, leaving the device unable to
     * recover until the user moved. With the hardened policy these fixes
     * must hold the counter (no advance, no reset) and never pause.
     */
    @Test
    fun stationaryUpdate_filterIntervenedFixes_doNotPause() {
        val anchor = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 0L
            accuracy = 27.55f
        }
        val noisy = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 40_000L
            accuracy = 27.55f
        }
        var consecutive = 1
        repeat(5) {
            val result = TrackingLocationPolicy.stationaryUpdate(
                lastLocation = anchor,
                location = noisy,
                stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
                currentConsecutive = consecutive,
                significantMotionOnly = true,
                filterIntervened = true,
            )
            assertEquals(consecutive, result.consecutive)
            assertFalse(result.shouldPause)
            assertEquals("filter_intervened", result.reason)
            consecutive = result.consecutive
        }
    }

    @Test
    fun stationaryUpdate_poorAccuracy_holdsCounterAndDoesNotPause() {
        val anchor = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 0L
            accuracy = 8f
        }
        val poor = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 20_000L
            accuracy = 50f
        }
        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor,
            location = poor,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            currentConsecutive = 2,
            significantMotionOnly = true,
        )
        assertEquals(2, result.consecutive)
        assertFalse(result.shouldPause)
        assertEquals("poor_accuracy", result.reason)
    }

    @Test
    fun stationaryUpdate_goodAccuracyClusteredFixes_pausesAfterThree() {
        val anchor = Location("test").apply {
            latitude = 47.6062
            longitude = -122.3321
            time = 0L
            accuracy = 6f
        }
        val close = Location("test").apply {
            latitude = 47.6062
            longitude = -122.3321
            time = 20_000L
            accuracy = 7f
        }
        var consecutive = 1
        for (i in 0 until 2) {
            val result = TrackingLocationPolicy.stationaryUpdate(
                lastLocation = anchor,
                location = close,
                stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
                currentConsecutive = consecutive,
                significantMotionOnly = true,
            )
            consecutive = result.consecutive
            if (i < 1) assertFalse(result.shouldPause)
            else assertTrue(result.shouldPause)
        }
    }

    @Test
    fun stationaryUpdate_radiusNotInflatedByAccuracy() {
        // A 60 m drift between anchor and fix must reset the counter even
        // though the fix has 100 m accuracy. The old policy inflated the
        // radius to max(50, 100) = 100 m, swallowing real movement; the
        // hardened policy rejects the fix entirely (poor_accuracy) and
        // holds the counter without falsely affirming stationarity.
        val anchor = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 0L
            accuracy = 8f
        }
        val drifted = Location("test").apply {
            latitude = 0.00054 // ~60 m
            longitude = 0.0
            time = 20_000L
            accuracy = 100f
        }
        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor,
            location = drifted,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            currentConsecutive = 2,
            significantMotionOnly = true,
        )
        assertEquals(2, result.consecutive)
        assertFalse(result.shouldPause)
        assertEquals("poor_accuracy", result.reason)
    }
}
