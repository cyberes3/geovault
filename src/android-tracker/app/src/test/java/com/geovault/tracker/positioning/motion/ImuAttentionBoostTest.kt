package com.geovault.tracker.positioning.motion

import com.geovault.tracker.sensor.ImuClassification
import com.geovault.tracker.services.TrackingMotionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MotionSubsystem.computeBoostNeeded].
 *
 * The IMU attention boost tightens the GPS location request when IMU and GPS mode disagree,
 * prompting the GPS system to accumulate evidence faster. It is deliberately mode-agnostic:
 * any IMU/GPS disagreement triggers the boost, uniformly, regardless of which specific modes
 * are involved.
 */
class ImuAttentionBoostTest {

    // region PEDESTRIAN classification

    @Test
    fun pedestrian_withWalkingMode_noBoost() {
        assertFalse(
            MotionSubsystem.computeBoostNeeded(ImuClassification.PEDESTRIAN, TrackingMotionMode.WALKING)
        )
    }

    @Test
    fun pedestrian_withBikingMode_boost() {
        assertTrue(
            MotionSubsystem.computeBoostNeeded(ImuClassification.PEDESTRIAN, TrackingMotionMode.BIKING)
        )
    }

    @Test
    fun pedestrian_withDrivingMode_boost() {
        assertTrue(
            MotionSubsystem.computeBoostNeeded(ImuClassification.PEDESTRIAN, TrackingMotionMode.DRIVING)
        )
    }

    // endregion

    // region VEHICULAR classification

    @Test
    fun vehicular_withWalkingMode_boost() {
        assertTrue(
            MotionSubsystem.computeBoostNeeded(ImuClassification.VEHICULAR, TrackingMotionMode.WALKING)
        )
    }

    @Test
    fun vehicular_withBikingMode_noBoost() {
        assertFalse(
            MotionSubsystem.computeBoostNeeded(ImuClassification.VEHICULAR, TrackingMotionMode.BIKING)
        )
    }

    @Test
    fun vehicular_withDrivingMode_noBoost() {
        assertFalse(
            MotionSubsystem.computeBoostNeeded(ImuClassification.VEHICULAR, TrackingMotionMode.DRIVING)
        )
    }

    // endregion

    // region STATIONARY and UNKNOWN classifications — never trigger boost

    @Test
    fun stationary_withAnyMode_noBoost() {
        assertFalse(
            MotionSubsystem.computeBoostNeeded(ImuClassification.STATIONARY, TrackingMotionMode.WALKING)
        )
        assertFalse(
            MotionSubsystem.computeBoostNeeded(ImuClassification.STATIONARY, TrackingMotionMode.BIKING)
        )
        assertFalse(
            MotionSubsystem.computeBoostNeeded(ImuClassification.STATIONARY, TrackingMotionMode.DRIVING)
        )
    }

    @Test
    fun unknown_withAnyMode_noBoost() {
        assertFalse(
            MotionSubsystem.computeBoostNeeded(ImuClassification.UNKNOWN, TrackingMotionMode.WALKING)
        )
        assertFalse(
            MotionSubsystem.computeBoostNeeded(ImuClassification.UNKNOWN, TrackingMotionMode.BIKING)
        )
        assertFalse(
            MotionSubsystem.computeBoostNeeded(ImuClassification.UNKNOWN, TrackingMotionMode.DRIVING)
        )
    }

    // endregion
}
