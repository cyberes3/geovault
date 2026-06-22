package com.geovault.tracker

import android.location.Location
import com.geovault.tracker.policy.filter.StationaryConfidence
import com.geovault.tracker.sensor.ImuClassification
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
    fun stationaryUpdate_activeSpeedHint_resetsAndNoPause() {
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
            activeSpeedHint = true,
        )

        assertEquals(0, result.consecutive)
        assertFalse(result.shouldPause)
    }

    /**
     * Generic [filterIntervened] below the pause threshold: the counter is
     * held — neither advanced into a false pause nor reset away from
     * legitimate progress. Original contract preserved.
     */
    @Test
    fun stationaryUpdate_filterIntervened_belowThreshold_doesNotPause() {
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
        // Drive consecutive up to PAUSE_THRESHOLD - 1 (= 2); it must stay there.
        repeat(4) {
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
            // Counter is held, not incremented; cap test at 2 to stay below threshold.
            consecutive = minOf(result.consecutive, 2)
        }
    }

    /**
     * When [filterIntervened] is true but the counter has already reached the
     * pause threshold (e.g. after a brief motion-resume), the pause decision
     * must still be honoured so GPS can re-sleep without requiring a new
     * non-intervened fix.
     */
    @Test
    fun stationaryUpdate_filterIntervened_alreadyAtThreshold_pauses() {
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
        // consecutive = 3 = PAUSE_THRESHOLD
        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor,
            location = noisy,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            currentConsecutive = 3,
            significantMotionOnly = true,
            filterIntervened = true,
        )
        assertEquals(3, result.consecutive)
        assertTrue(result.shouldPause)
        assertEquals("filter_intervened", result.reason)
    }

    /**
     * When [filterIntervened] is true but sensor-fusion confidence exceeds the
     * fast-advance threshold, the counter jumps to PAUSE_THRESHOLD and GPS
     * pauses. The IMU/barometer signal is GPS-independent and does not require
     * a fresh committed trail.
     */
    @Test
    fun stationaryUpdate_filterIntervened_highConfidence_fastAdvances() {
        val anchor = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 0L
            accuracy = 27.55f
        }
        val noisy = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 120_000L
            accuracy = 27.55f
        }
        // consecutive = 1 (anchor established), confidence just above FAST_ADVANCE_SCORE (0.6)
        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor,
            location = noisy,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            currentConsecutive = 1,
            significantMotionOnly = true,
            filterIntervened = true,
            confidence = StationaryConfidence(score = 0.65, isStationary = true, isOscillating = false),
        )
        assertEquals(3, result.consecutive)
        assertTrue(result.shouldPause)
        assertEquals("confidence_fast_advance", result.reason)
    }

    /**
     * When [filterIntervened] is true and confidence is high but no anchor has
     * been established yet (consecutive = 0), the fast-advance must NOT fire
     * because there is no reference position.
     */
    @Test
    fun stationaryUpdate_filterIntervened_highConfidence_noAnchor_doesNotPause() {
        val noisy = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 40_000L
            accuracy = 27.55f
        }
        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = null,
            location = noisy,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            currentConsecutive = 0,
            significantMotionOnly = true,
            filterIntervened = true,
            confidence = StationaryConfidence(score = 0.65, isStationary = true, isOscillating = false),
        )
        assertFalse(result.shouldPause)
    }

    /**
     * There is no separate stationary accuracy gate. If the upstream
     * filter accepted the fix, the policy uses it. A 50 m indoor-accuracy
     * fix at the anchor (raw distance 0) is unambiguous stillness
     * evidence -- the joint accuracy envelope subsumes the displacement,
     * so the accuracy-defensive radius math reads it as within-radius and
     * advances the counter.
     */
    @Test
    fun stationaryUpdate_indoorAccuracyAtAnchor_advancesCounter() {
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
        assertEquals(3, result.consecutive)
        assertTrue(result.shouldPause)
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

    /**
     * Accuracy-defensive radius: the joint accuracy envelope is subtracted
     * from raw distance before comparing against the radius. A 60 m drift
     * with 100 m fix accuracy + 8 m anchor accuracy is geometrically
     * indistinguishable from "didn't move at all", so the counter advances
     * rather than resetting on what may well be pure uncertainty.
     */
    @Test
    fun stationaryUpdate_accuracyEatenRadius_advancesCounter() {
        val anchor = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 0L
            accuracy = 8f
        }
        val drifted = Location("test").apply {
            latitude = 0.00054
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
        assertEquals(3, result.consecutive)
        assertTrue(result.shouldPause)
    }

    /**
     * `filterConfirmedStillness` (filter snapped to anchor as
     * `uncertainty-suppressed`) is positive evidence the device hasn't
     * moved. It must advance the counter even when `activeSpeedHint`
     * would otherwise reset it.
     */
    @Test
    fun stationaryUpdate_filterConfirmedStillness_advancesEvenWithActiveSpeedHint() {
        val anchor = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 0L
            accuracy = 8f
        }
        val confirmed = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 20_000L
            accuracy = 12f
        }
        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor,
            location = confirmed,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            currentConsecutive = 2,
            significantMotionOnly = true,
            activeSpeedHint = true,
            filterConfirmedStillness = true,
        )
        assertEquals(3, result.consecutive)
        assertTrue(result.shouldPause)
        assertEquals("pause_threshold_reached", result.reason)
    }

    /**
     * Phantom 0.9 m/s reported speed (within the 1.0 m/s motion floor)
     * with no geometric displacement must not reset the stationary
     * counter. Tightened from the old 0.75 m/s floor that let typical
     * indoor multipath bursts permanently block pause.
     */
    @Test
    fun stationaryUpdate_phantomNinePointZeroSpeed_belowFloor_doesNotReset() {
        val anchor = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 0L
            accuracy = 8f
        }
        val phantom = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 5_000L
            accuracy = 8f
            speed = 0.9f
        }
        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor,
            location = phantom,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            currentConsecutive = 2,
            significantMotionOnly = true,
        )
        assertEquals(3, result.consecutive)
        assertTrue(result.shouldPause)
    }

    /**
     * Multi-signal confidence above 0.6 fast-advances the counter past
     * the 3-tick threshold so we don't waste a minute of polling proving
     * what we already know. Requires [currentConsecutive] > 0 — confidence
     * accelerates existing GPS evidence but cannot create the first anchor.
     */
    @Test
    fun stationaryUpdate_highConfidence_fastAdvancesToPause() {
        val anchor = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 0L
            accuracy = 8f
        }
        val close = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 20_000L
            accuracy = 8f
        }
        val confidence = StationaryConfidence(score = 0.85, isStationary = true, isOscillating = false)
        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor,
            location = close,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            currentConsecutive = 1,
            significantMotionOnly = true,
            confidence = confidence,
        )
        assertTrue(result.consecutive >= 3)
        assertTrue(result.shouldPause)
        assertEquals("confidence_fast_advance", result.reason)
    }

    /**
     * Confident rubber-band oscillation (score > 0.5, isOscillating
     * true) is the textbook indoor-multipath signature -- jump straight
     * to pause rather than waiting for raw geometry to confirm.
     * Requires [currentConsecutive] > 0.
     */
    @Test
    fun stationaryUpdate_oscillatingConfidence_fastAdvancesToPause() {
        val anchor = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 0L
            accuracy = 12f
        }
        val close = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 20_000L
            accuracy = 12f
        }
        val confidence = StationaryConfidence(score = 0.55, isStationary = true, isOscillating = true)
        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor,
            location = close,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            currentConsecutive = 1,
            significantMotionOnly = true,
            confidence = confidence,
        )
        assertTrue(result.shouldPause)
        assertEquals("confidence_fast_advance", result.reason)
    }

    /**
     * The fast-advance invariant: sensor-fusion confidence requires [currentConsecutive] > 0
     * to fire. When the counter is 0 (no GPS anchor yet), confidence is ignored and the
     * counter advances by 1 normally. This prevents an immediate re-pause after a
     * significant-motion reset where the device is genuinely still.
     */
    @Test
    fun stationaryUpdate_confidenceFastAdvance_doesNotFireWhenCounterIsZero() {
        val anchor = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 0L
            accuracy = 8f
        }
        val close = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 20_000L
            accuracy = 8f
        }
        val confidence = StationaryConfidence(score = 0.90, isStationary = true, isOscillating = false)
        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor,
            location = close,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            currentConsecutive = 0,
            significantMotionOnly = true,
            confidence = confidence,
        )
        assertEquals(1, result.consecutive)
        assertFalse(result.shouldPause)
    }

    /**
     * Companion to the counter=0 test above: once at least one GPS fix has established
     * the anchor (counter=1), the same high confidence correctly jumps straight to the
     * pause threshold.
     */
    @Test
    fun stationaryUpdate_confidenceFastAdvance_firesWhenCounterIsOne() {
        val anchor = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 0L
            accuracy = 8f
        }
        val close = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            time = 20_000L
            accuracy = 8f
        }
        val confidence = StationaryConfidence(score = 0.90, isStationary = true, isOscillating = false)
        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor,
            location = close,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            currentConsecutive = 1,
            significantMotionOnly = true,
            confidence = confidence,
        )
        assertEquals(3, result.consecutive)
        assertTrue(result.shouldPause)
        assertEquals("confidence_fast_advance", result.reason)
    }

    // region IMU classification integration

    @Test
    fun stationaryUpdate_imuPedestrian_blocksGpsPause() {
        // Without IMU, three stationary fixes → pause. With PEDESTRIAN IMU, must not pause.
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 5f }
        val close1 = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 10_000L; accuracy = 5f }
        val close2 = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 20_000L; accuracy = 5f }

        val r1 = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = null, location = anchor,
            stationaryRadiusMeters = 50f, currentConsecutive = 0,
            significantMotionOnly = true, imuClassification = ImuClassification.PEDESTRIAN,
        )
        assertEquals(0, r1.consecutive)
        assertFalse(r1.shouldPause)
        assertEquals("active_speed_hint", r1.reason)

        val r2 = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = close1,
            stationaryRadiusMeters = 50f, currentConsecutive = 0,
            significantMotionOnly = true, imuClassification = ImuClassification.PEDESTRIAN,
        )
        assertEquals(0, r2.consecutive)
        assertFalse(r2.shouldPause)

        val r3 = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = close1, location = close2,
            stationaryRadiusMeters = 50f, currentConsecutive = 0,
            significantMotionOnly = true, imuClassification = ImuClassification.PEDESTRIAN,
        )
        assertEquals(0, r3.consecutive)
        assertFalse(r3.shouldPause)
    }

    @Test
    fun stationaryUpdate_imuPedestrian_filterConfirmedStillnessStillPauses() {
        // filterConfirmedStillness supersedes the IMU PEDESTRIAN active-speed-hint.
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 5f }
        val same = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 10_000L; accuracy = 5f }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = same,
            stationaryRadiusMeters = 50f, currentConsecutive = 2,
            significantMotionOnly = true,
            filterConfirmedStillness = true,
            imuClassification = ImuClassification.PEDESTRIAN,
        )
        // consecutive=3, shouldPause=true (filterConfirmedStillness wins over PEDESTRIAN)
        assertEquals(3, result.consecutive)
        assertTrue(result.shouldPause)
    }

    @Test
    fun stationaryUpdate_imuStationary_fastAdvancesCounter() {
        // IMU STATIONARY contributes to confidenceCanFastAdvance even without GPS confidence.
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 5f }
        val close = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 10_000L; accuracy = 5f }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = close,
            stationaryRadiusMeters = 50f, currentConsecutive = 1,  // counter > 0 required
            significantMotionOnly = true,
            imuClassification = ImuClassification.STATIONARY,
        )
        // fast-advance: consecutive jumps to at least PAUSE_THRESHOLD=3
        assertEquals(3, result.consecutive)
        assertTrue(result.shouldPause)
        assertEquals("confidence_fast_advance", result.reason)
    }

    @Test
    fun stationaryUpdate_imuStationary_doesNotFastAdvanceWhenCounterIsZero() {
        // Fast-advance requires currentConsecutive > 0 even with IMU STATIONARY.
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 5f }
        val close = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 10_000L; accuracy = 5f }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = close,
            stationaryRadiusMeters = 50f, currentConsecutive = 0,  // counter = 0 → no fast-advance
            significantMotionOnly = true,
            imuClassification = ImuClassification.STATIONARY,
        )
        // Normal advance: consecutive=1, no pause
        assertEquals(1, result.consecutive)
        assertFalse(result.shouldPause)
    }

    @Test
    fun stationaryUpdate_imuUnknown_behavesLikeNoImu() {
        // UNKNOWN classification should have no effect on stationary logic.
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 5f }
        val close = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 10_000L; accuracy = 5f }

        val withImu = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = close,
            stationaryRadiusMeters = 50f, currentConsecutive = 1,
            significantMotionOnly = true,
            imuClassification = ImuClassification.UNKNOWN,
        )
        val withoutImu = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = close,
            stationaryRadiusMeters = 50f, currentConsecutive = 1,
            significantMotionOnly = true,
        )
        assertEquals(withoutImu.consecutive, withImu.consecutive)
        assertEquals(withoutImu.shouldPause, withImu.shouldPause)
        assertEquals(withoutImu.reason, withImu.reason)
    }

    @Test
    fun stationaryUpdate_imuVehicular_blocksGpsPause() {
        // IMU VEHICULAR acts as an active-speed hint: the stationary counter resets to 0 and
        // GPS pause is suppressed, mirroring the PEDESTRIAN behaviour.
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 5f }
        val close1 = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 10_000L; accuracy = 5f }
        val close2 = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 20_000L; accuracy = 5f }

        val r1 = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = null, location = anchor,
            stationaryRadiusMeters = 50f, currentConsecutive = 0,
            significantMotionOnly = true, imuClassification = ImuClassification.VEHICULAR,
        )
        assertEquals(0, r1.consecutive)
        assertFalse(r1.shouldPause)
        assertEquals("active_speed_hint", r1.reason)

        val r2 = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = close1,
            stationaryRadiusMeters = 50f, currentConsecutive = 0,
            significantMotionOnly = true, imuClassification = ImuClassification.VEHICULAR,
        )
        assertEquals(0, r2.consecutive)
        assertFalse(r2.shouldPause)

        val r3 = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = close1, location = close2,
            stationaryRadiusMeters = 50f, currentConsecutive = 0,
            significantMotionOnly = true, imuClassification = ImuClassification.VEHICULAR,
        )
        assertEquals(0, r3.consecutive)
        assertFalse(r3.shouldPause)
    }

    @Test
    fun stationaryUpdate_imuVehicular_filterConfirmedStillnessStillPauses() {
        // filterConfirmedStillness supersedes the IMU VEHICULAR active-speed-hint — GPS geometry
        // proving the device hasn't moved is stronger evidence than the IMU alone.
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 5f }
        val same = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 10_000L; accuracy = 5f }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = same,
            stationaryRadiusMeters = 50f, currentConsecutive = 2,
            significantMotionOnly = true,
            filterConfirmedStillness = true,
            imuClassification = ImuClassification.VEHICULAR,
        )
        assertEquals(3, result.consecutive)
        assertTrue(result.shouldPause)
    }

    @Test
    fun stationaryUpdate_imuStationary_filterIntervenedFastAdvance() {
        // IMU STATIONARY should fast-advance even when filterIntervened=true (GPS-independent signal).
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 5f }
        val location = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 10_000L; accuracy = 5f }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = location,
            stationaryRadiusMeters = 50f, currentConsecutive = 1,
            significantMotionOnly = true,
            filterIntervened = true,
            imuClassification = ImuClassification.STATIONARY,
        )
        assertEquals(3, result.consecutive)
        assertTrue(result.shouldPause)
        assertEquals("confidence_fast_advance", result.reason)
    }

    // --- additional IMU edge cases ---

    /**
     * IMU PEDESTRIAN active-speed-hint is suppressed when [significantMotionOnly] is false —
     * the entire stationary system is disabled regardless of IMU state.
     */
    @Test
    fun stationaryUpdate_pedestrianImu_significantMotionFalse_returnsDisabled() {
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 5f }
        val close = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 5_000L; accuracy = 5f }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = close,
            stationaryRadiusMeters = 50f, currentConsecutive = 0,
            significantMotionOnly = false,
            imuClassification = ImuClassification.PEDESTRIAN,
        )
        assertEquals(0, result.consecutive)
        assertFalse(result.shouldPause)
        assertEquals("disabled", result.reason)
    }

    /**
     * IMU STATIONARY with [currentConsecutive] already at the pause threshold (3):
     * fast-advance clamps to max(4, 3)=4 but [shouldPause] is already true, so the
     * result must remain paused and the counter must not drop below the threshold.
     */
    @Test
    fun stationaryUpdate_imuStationary_counterAlreadyAtThreshold_remainsPaused() {
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 5f }
        val close = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 5_000L; accuracy = 5f }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = close,
            stationaryRadiusMeters = 50f, currentConsecutive = 3,
            significantMotionOnly = true,
            imuClassification = ImuClassification.STATIONARY,
        )
        assertTrue(result.shouldPause)
        assertTrue("counter must not drop below threshold", result.consecutive >= 3)
    }

    /**
     * IMU STATIONARY must fast-advance the counter even when the fix is geometrically
     * outside the stationary radius. The IMU signal is GPS-independent — it
     * provides evidence of stillness even when the GPS anchor is stale or drifted.
     */
    @Test
    fun stationaryUpdate_imuStationary_outsideRadius_fastAdvancesDespiteGeometry() {
        val anchor = Location("test").apply {
            latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 5f
        }
        val farAway = Location("test").apply {
            // ~111 m away — clearly outside a 50 m radius
            latitude = 0.001; longitude = 0.0; time = 5_000L; accuracy = 5f
        }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = farAway,
            stationaryRadiusMeters = 50f, currentConsecutive = 2,
            significantMotionOnly = true,
            imuClassification = ImuClassification.STATIONARY,
        )
        assertEquals(3, result.consecutive)
        assertTrue(result.shouldPause)
        assertEquals("confidence_fast_advance", result.reason)
    }

    /**
     * When both [activeSpeedHint] and [imuClassification]=PEDESTRIAN are true the
     * result is the same active-speed-hint early return — no double handling.
     */
    @Test
    fun stationaryUpdate_pedestrianImuAndActiveSpeedHintBothTrue_returnsActiveSpeedHint() {
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 5f }
        val close = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 5_000L; accuracy = 5f }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = close,
            stationaryRadiusMeters = 50f, currentConsecutive = 2,
            significantMotionOnly = true,
            activeSpeedHint = true,
            imuClassification = ImuClassification.PEDESTRIAN,
        )
        assertEquals(0, result.consecutive)
        assertFalse(result.shouldPause)
        assertEquals("active_speed_hint", result.reason)
    }

    /**
     * A null [imuClassification] (no IMU context available) must be identical in
     * behaviour to passing [ImuClassification.UNKNOWN] — it must not affect the
     * stationary counter in any way.
     */
    @Test
    fun stationaryUpdate_nullImu_identicalToUnknownImu() {
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 5f }
        val close = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 5_000L; accuracy = 5f }

        val withNull = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = close,
            stationaryRadiusMeters = 50f, currentConsecutive = 1,
            significantMotionOnly = true, imuClassification = null,
        )
        val withUnknown = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = close,
            stationaryRadiusMeters = 50f, currentConsecutive = 1,
            significantMotionOnly = true, imuClassification = ImuClassification.UNKNOWN,
        )
        assertEquals(withNull.consecutive, withUnknown.consecutive)
        assertEquals(withNull.shouldPause, withUnknown.shouldPause)
        assertEquals(withNull.reason, withUnknown.reason)
    }

    /**
     * IMU PEDESTRIAN must block GPS pause even when the fix is geometrically
     * outside the stationary radius — the active-speed-hint fires before the
     * geometry check, so location drift cannot falsely override a walking user.
     */
    @Test
    fun stationaryUpdate_pedestrianImu_outsideRadius_stillBlocksPause() {
        val anchor = Location("test").apply {
            latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 5f
        }
        val farAway = Location("test").apply {
            latitude = 0.001; longitude = 0.0; time = 5_000L; accuracy = 5f
        }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = farAway,
            stationaryRadiusMeters = 50f, currentConsecutive = 2,
            significantMotionOnly = true,
            imuClassification = ImuClassification.PEDESTRIAN,
        )
        assertEquals(0, result.consecutive)
        assertFalse(result.shouldPause)
        assertEquals("active_speed_hint", result.reason)
    }

    // endregion

    // region Post-departure cooldown

    /**
     * When [inMotionCooldown] is true, [stationaryUpdate] must return
     * consecutive=0 / shouldPause=false / reason="motion_exit_cooldown"
     * unconditionally, regardless of any other input. This is the primary
     * regression guard for the bug where UNCERTAINTY_SUPPRESSED fixes near
     * a previous parked location re-established a stationary region within
     * 12 seconds of departure, causing a 560 m jump.
     */
    @Test
    fun stationaryUpdate_inMotionCooldown_returnsZeroAndNoPause() {
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 5f }
        val close = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 5_000L; accuracy = 5f }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = close,
            stationaryRadiusMeters = 50f, currentConsecutive = 2,
            significantMotionOnly = true,
            inMotionCooldown = true,
        )

        assertEquals(0, result.consecutive)
        assertFalse(result.shouldPause)
        assertEquals("motion_exit_cooldown", result.reason)
    }

    /**
     * UNCERTAINTY_SUPPRESSED fixes (filterConfirmedStillness=true) near the
     * previous parked location must not advance the counter during cooldown.
     * This is the exact path that triggered the premature re-pause observed
     * in the field (counter 0→3 in 12 s via filterConfirmedStillness).
     */
    @Test
    fun stationaryUpdate_inMotionCooldown_filterConfirmedStillness_doesNotAccumulate() {
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 2f }
        val snap = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 5_000L; accuracy = 1f }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = snap,
            stationaryRadiusMeters = 50f, currentConsecutive = 0,
            significantMotionOnly = true,
            filterConfirmedStillness = true,
            inMotionCooldown = true,
        )

        assertEquals(0, result.consecutive)
        assertFalse(result.shouldPause)
        assertEquals("motion_exit_cooldown", result.reason)
    }

    /**
     * After the cooldown window expires (inMotionCooldown=false), the stationary
     * machine resumes from zero. The first fix with no prior anchor produces
     * first_anchor (consecutive=1, no pause), confirming a clean slate.
     */
    @Test
    fun stationaryUpdate_cooldownExpired_resumesNormally() {
        val firstFix = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 35_000L; accuracy = 8f }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = null, location = firstFix,
            stationaryRadiusMeters = 50f, currentConsecutive = 0,
            significantMotionOnly = true,
            inMotionCooldown = false,
        )

        assertEquals(1, result.consecutive)
        assertFalse(result.shouldPause)
        assertEquals("first_anchor", result.reason)
    }

    // endregion

    // region GPS Doppler speed gate on confidence_fast_advance

    /**
     * GPS Doppler speed above [GPS_MOTION_FLOOR_MPS] (1.0 m/s) combined with
     * [filterConfirmedStillness]=true is a contradictory signal pair.
     * The counter must be **held** (neither advanced nor reset) until the signals
     * converge. This prevents slow accumulation of a false pause when accuracy
     * inflation causes UNCERTAINTY_SUPPRESSED snaps while the device is actually
     * moving (~2 m/s).
     */
    @Test
    fun stationaryUpdate_gpsDopplerSpeedAboveFloor_holdsCounter() {
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 15f }
        // Same position as anchor but with GPS Doppler speed 2.0 m/s — the device is moving
        // but accuracy inflation caused a SNAP_INTERNAL snap (filterConfirmedStillness=true).
        val moving = Location("test").apply {
            latitude = 0.0; longitude = 0.0; time = 10_000L; accuracy = 15f
            speed = 2.0f
        }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = moving,
            stationaryRadiusMeters = 50f, currentConsecutive = 1,
            significantMotionOnly = true,
            filterConfirmedStillness = true,
            imuClassification = ImuClassification.STATIONARY,
        )

        // Both fast-advance and base-advance are blocked: counter holds at 1.
        assertEquals(1, result.consecutive)
        assertFalse(result.shouldPause)
        assertEquals("doppler_contradicts_confirmed_stillness", result.reason)
    }

    /**
     * GPS Doppler speed below [GPS_MOTION_FLOOR_MPS] (phantom burst at 0.9 m/s) must
     * NOT block [confidenceFastAdvance]. Below the floor, speed is unreliable (chipset
     * noise at rest); geometry and IMU are the trusted signals.
     */
    @Test
    fun stationaryUpdate_gpsDopplerSpeedAtFloor_fastAdvanceStillFires() {
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 15f }
        val phantom = Location("test").apply {
            latitude = 0.0; longitude = 0.0; time = 10_000L; accuracy = 15f
            speed = 0.9f  // below floor → movedByGpsSpeed=false → gate stays open
        }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = phantom,
            stationaryRadiusMeters = 50f, currentConsecutive = 1,
            significantMotionOnly = true,
            filterConfirmedStillness = true,
            imuClassification = ImuClassification.STATIONARY,
        )

        // fast-advance fires: counter jumps to PAUSE_THRESHOLD=3 and GPS pauses.
        assertEquals(3, result.consecutive)
        assertTrue(result.shouldPause)
        assertEquals("confidence_fast_advance", result.reason)
    }

    // endregion

    // region IMU fast-advance cooldown after vehicular wake

    /**
     * When [imuFastAdvanceCooldown] is active, the [ImuClassification.STATIONARY]
     * arm of [confidenceCanFastAdvance] must be suppressed. Counter advances by 1
     * (base advance only) rather than jumping to the pause threshold.
     *
     * Real-world scenario: IMU oscillates back to STATIONARY within one
     * classification window after an imu_vehicular_wake while the vehicle is still
     * moving. Without the cooldown this would immediately re-pause GPS.
     */
    @Test
    fun stationaryUpdate_imuFastAdvanceCooldown_blocksImuStationary() {
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 15f }
        val fixed = Location("test").apply {
            latitude = 0.0; longitude = 0.0; time = 10_000L; accuracy = 15f
            // speed=-1 (no Doppler) so Doppler gate does not apply
        }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = fixed,
            stationaryRadiusMeters = 50f, currentConsecutive = 1,
            significantMotionOnly = true,
            filterConfirmedStillness = true,
            imuClassification = ImuClassification.STATIONARY,
            imuFastAdvanceCooldown = true,
        )

        // IMU arm suppressed: counter advances by 1 only, no pause.
        assertEquals(2, result.consecutive)
        assertFalse(result.shouldPause)
        assertEquals("advance_confirmed_stillness", result.reason)
    }

    /**
     * When [imuFastAdvanceCooldown] is active, the GPS confidence-score arm of
     * [confidenceCanFastAdvance] must still fire normally. Only the IMU=STATIONARY
     * arm is suppressed; genuinely high GPS confidence (score > 0.6) is still trusted.
     */
    @Test
    fun stationaryUpdate_imuFastAdvanceCooldown_doesNotBlockHighConfidence() {
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 15f }
        val fixed = Location("test").apply {
            latitude = 0.0; longitude = 0.0; time = 10_000L; accuracy = 15f
        }
        val highConfidence = StationaryConfidence(score = 0.85, isStationary = true, isOscillating = false)

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = fixed,
            stationaryRadiusMeters = 50f, currentConsecutive = 1,
            significantMotionOnly = true,
            filterConfirmedStillness = true,
            imuClassification = null,
            confidence = highConfidence,
            imuFastAdvanceCooldown = true,
        )

        // High GPS confidence score overrides cooldown: counter jumps to PAUSE_THRESHOLD=3.
        assertEquals(3, result.consecutive)
        assertTrue(result.shouldPause)
        assertEquals("confidence_fast_advance", result.reason)
    }

    /**
     * Without the cooldown ([imuFastAdvanceCooldown]=false), IMU=STATIONARY must still
     * trigger fast-advance as before. Regression guard.
     */
    @Test
    fun stationaryUpdate_imuFastAdvanceCooldownFalse_imuStationaryStillFires() {
        val anchor = Location("test").apply { latitude = 0.0; longitude = 0.0; time = 0L; accuracy = 15f }
        val fixed = Location("test").apply {
            latitude = 0.0; longitude = 0.0; time = 10_000L; accuracy = 15f
        }

        val result = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = anchor, location = fixed,
            stationaryRadiusMeters = 50f, currentConsecutive = 1,
            significantMotionOnly = true,
            filterConfirmedStillness = true,
            imuClassification = ImuClassification.STATIONARY,
            imuFastAdvanceCooldown = false,
        )

        // No cooldown: IMU=STATIONARY fast-advance fires normally.
        assertEquals(3, result.consecutive)
        assertTrue(result.shouldPause)
        assertEquals("confidence_fast_advance", result.reason)
    }

    // endregion
}
