package com.geovault.tracker.positioning.motion

import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.policy.filter.StationaryConfidence
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

    // region computeSensorFusionHighConfidence

    @Test
    fun imuStationary_noGpsConfidence_highConfidence() {
        // IMU STATIONARY independently bypasses the STALE_LOCAL_POINT gate even when
        // GPS-derived stationary confidence is absent.
        assertTrue(
            MotionSubsystem.computeSensorFusionHighConfidence(
                stationaryConfidence = null,
                imuClassification = ImuClassification.STATIONARY,
            )
        )
    }

    @Test
    fun imuPedestrian_noGpsConfidence_notHighConfidence() {
        assertFalse(
            MotionSubsystem.computeSensorFusionHighConfidence(
                stationaryConfidence = null,
                imuClassification = ImuClassification.PEDESTRIAN,
            )
        )
    }

    @Test
    fun imuVehicular_noGpsConfidence_notHighConfidence() {
        assertFalse(
            MotionSubsystem.computeSensorFusionHighConfidence(
                stationaryConfidence = null,
                imuClassification = ImuClassification.VEHICULAR,
            )
        )
    }

    @Test
    fun imuUnknown_noGpsConfidence_notHighConfidence() {
        assertFalse(
            MotionSubsystem.computeSensorFusionHighConfidence(
                stationaryConfidence = null,
                imuClassification = ImuClassification.UNKNOWN,
            )
        )
    }

    @Test
    fun noImu_noGpsConfidence_notHighConfidence() {
        assertFalse(
            MotionSubsystem.computeSensorFusionHighConfidence(
                stationaryConfidence = null,
                imuClassification = null,
            )
        )
    }

    @Test
    fun imuStationary_withLowGpsConfidence_stillHighConfidence() {
        // IMU STATIONARY should dominate regardless of GPS confidence level.
        val lowConfidence = StationaryConfidence(score = 0.1, isStationary = false, isOscillating = false)
        assertTrue(
            MotionSubsystem.computeSensorFusionHighConfidence(
                stationaryConfidence = lowConfidence,
                imuClassification = ImuClassification.STATIONARY,
            )
        )
    }

    @Test
    fun noImu_highGpsConfidence_highConfidence() {
        // GPS-derived high confidence still triggers the bypass when IMU is absent.
        val highConfidence = StationaryConfidence(score = 0.9, isStationary = true, isOscillating = false)
        assertTrue(
            MotionSubsystem.computeSensorFusionHighConfidence(
                stationaryConfidence = highConfidence,
                imuClassification = null,
            )
        )
    }

    // endregion

    // region computeTransitionBoostNeeded

    /**
     * The first classification emitted in a session has no previous context.
     * There is no transition, so no boost should fire.
     */
    @Test
    fun transitionBoost_nullPrevious_doesNotFire() {
        assertFalse(
            MotionSubsystem.computeTransitionBoostNeeded(
                previousClassification = null,
                newClassification = ImuClassification.PEDESTRIAN,
                lastTransitionBoostAtMs = 0L,
                nowMs = 60_000L,
            )
        )
    }

    /**
     * The classifier re-emitting the same classification is not a transition.
     */
    @Test
    fun transitionBoost_sameClassification_doesNotFire() {
        assertFalse(
            MotionSubsystem.computeTransitionBoostNeeded(
                previousClassification = ImuClassification.VEHICULAR,
                newClassification = ImuClassification.VEHICULAR,
                lastTransitionBoostAtMs = 0L,
                nowMs = 60_000L,
            )
        )
    }

    /**
     * A genuine classification change with no recent prior transition boost fires the boost.
     * This is the primary case: any shift in IMU state warrants increased GPS sampling.
     */
    @Test
    fun transitionBoost_differentClassification_outsideDebounce_fires() {
        assertTrue(
            MotionSubsystem.computeTransitionBoostNeeded(
                previousClassification = ImuClassification.PEDESTRIAN,
                newClassification = ImuClassification.UNKNOWN,
                lastTransitionBoostAtMs = 0L,
                nowMs = TrackingLocationPolicy.IMU_TRANSITION_BOOST_DEBOUNCE_MS,
            )
        )
    }

    /**
     * A genuine transition within the debounce window must not re-arm the boost.
     * Guards against PEDESTRIAN→UNKNOWN→PEDESTRIAN oscillation re-triggering on every cycle.
     */
    @Test
    fun transitionBoost_differentClassification_withinDebounce_suppressed() {
        val boostFiredAtMs = 10_000L
        val nowMs = boostFiredAtMs + TrackingLocationPolicy.IMU_TRANSITION_BOOST_DEBOUNCE_MS - 1L
        assertFalse(
            MotionSubsystem.computeTransitionBoostNeeded(
                previousClassification = ImuClassification.UNKNOWN,
                newClassification = ImuClassification.PEDESTRIAN,
                lastTransitionBoostAtMs = boostFiredAtMs,
                nowMs = nowMs,
            )
        )
    }

    // endregion
}
