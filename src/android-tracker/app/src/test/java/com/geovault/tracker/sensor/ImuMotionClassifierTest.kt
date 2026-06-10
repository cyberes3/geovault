package com.geovault.tracker.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ImuMotionClassifier.classify]. Exercises the pure classification dispatch
 * via the companion function — no Android context, SensorManager, or hardware required.
 */
class ImuMotionClassifierTest {

    // region PEDESTRIAN

    @Test
    fun classify_stepRateAtMinimum_returnsPedestrian() {
        val (cls, _) = ImuMotionClassifier.classify(variance = 0f, stepRate = 40f, hasAccelData = true)
        assertEquals(ImuClassification.PEDESTRIAN, cls)
    }

    @Test
    fun classify_stepRateAboveMinimum_returnsPedestrian() {
        val (cls, _) = ImuMotionClassifier.classify(variance = 0.5f, stepRate = 60f, hasAccelData = true)
        assertEquals(ImuClassification.PEDESTRIAN, cls)
    }

    @Test
    fun classify_highStepRate_pedestrianConfidenceCappedAtOne() {
        val (cls, confidence) = ImuMotionClassifier.classify(variance = 0f, stepRate = 200f, hasAccelData = true)
        assertEquals(ImuClassification.PEDESTRIAN, cls)
        assertEquals(1.0f, confidence, 0.001f)
    }

    @Test
    fun classify_pedestrianTakesPriorityOverHighVariance() {
        // Enough steps to be PEDESTRIAN even with vehicular-level variance
        val (cls, _) = ImuMotionClassifier.classify(variance = 2.0f, stepRate = 60f, hasAccelData = true)
        assertEquals(ImuClassification.PEDESTRIAN, cls)
    }

    @Test
    fun classify_stepRateBelowMinimum_doesNotReturnPedestrian() {
        val (cls, _) = ImuMotionClassifier.classify(variance = 0f, stepRate = 39f, hasAccelData = true)
        // 39 < 40 → not PEDESTRIAN; variance=0 < 0.04 → STATIONARY
        assertEquals(ImuClassification.STATIONARY, cls)
    }

    // endregion

    // region STATIONARY

    @Test
    fun classify_zeroVarianceNoSteps_returnsStationaryWithMaxConfidence() {
        val (cls, confidence) = ImuMotionClassifier.classify(variance = 0f, stepRate = 0f, hasAccelData = true)
        assertEquals(ImuClassification.STATIONARY, cls)
        assertEquals(1.0f, confidence, 0.001f)
    }

    @Test
    fun classify_lowVarianceNoSteps_returnsStationary() {
        val (cls, _) = ImuMotionClassifier.classify(variance = 0.01f, stepRate = 0f, hasAccelData = true)
        assertEquals(ImuClassification.STATIONARY, cls)
    }

    @Test
    fun classify_varianceJustBelowCeiling_returnsStationary() {
        val (cls, _) = ImuMotionClassifier.classify(variance = 0.039f, stepRate = 0f, hasAccelData = true)
        assertEquals(ImuClassification.STATIONARY, cls)
    }

    @Test
    fun classify_stationaryConfidenceDecreasesAsVarianceRisesToCeiling() {
        val (_, confLow) = ImuMotionClassifier.classify(variance = 0.005f, stepRate = 0f, hasAccelData = true)
        val (_, confHigh) = ImuMotionClassifier.classify(variance = 0.035f, stepRate = 0f, hasAccelData = true)
        assertTrue("confidence should decrease as variance approaches ceiling", confLow > confHigh)
    }

    // endregion

    // region VEHICULAR

    @Test
    fun classify_highVarianceNoSteps_returnsVehicular() {
        val (cls, _) = ImuMotionClassifier.classify(variance = 1.0f, stepRate = 0f, hasAccelData = true)
        assertEquals(ImuClassification.VEHICULAR, cls)
    }

    @Test
    fun classify_aboveVarianceFloorWithFewSteps_returnsVehicular() {
        // stepRate = 5 < VEHICULAR_MAX_STEP_RATE(10), variance = 0.5 > VEHICULAR_VARIANCE_FLOOR(0.03)
        val (cls, _) = ImuMotionClassifier.classify(variance = 0.5f, stepRate = 5f, hasAccelData = true)
        assertEquals(ImuClassification.VEHICULAR, cls)
    }

    @Test
    fun classify_vehicularConfidenceIncreasesWithVariance() {
        val (_, lowConf) = ImuMotionClassifier.classify(variance = 0.04f, stepRate = 0f, hasAccelData = true)
        val (_, highConf) = ImuMotionClassifier.classify(variance = 0.2f, stepRate = 0f, hasAccelData = true)
        assertTrue("vehicular confidence should increase with variance above floor", highConf > lowConf)
    }

    // endregion

    // region UNKNOWN

    @Test
    fun classify_noAccelData_returnsUnknown() {
        val (cls, confidence) = ImuMotionClassifier.classify(variance = 0.5f, stepRate = 0f, hasAccelData = false)
        assertEquals(ImuClassification.UNKNOWN, cls)
        assertEquals(0.0f, confidence, 0.001f)
    }

    @Test
    fun classify_moderateStepRateBelowPedestrianThreshold_returnsUnknown() {
        // 25 steps/min: above VEHICULAR_MAX_STEP_RATE(10) so fails VEHICULAR, below PEDESTRIAN_STEP_RATE_MIN(40)
        val (cls, _) = ImuMotionClassifier.classify(variance = 0.5f, stepRate = 25f, hasAccelData = true)
        assertEquals(ImuClassification.UNKNOWN, cls)
    }

    @Test
    fun classify_stepRateAtVehicularMax_withHighVariance_returnsUnknown() {
        // stepRate = 10 which is NOT < VEHICULAR_MAX_STEP_RATE(10), so vehicular check fails
        val (cls, _) = ImuMotionClassifier.classify(variance = 0.5f, stepRate = 10f, hasAccelData = true)
        assertEquals(ImuClassification.UNKNOWN, cls)
    }

    // endregion

    // region Boundary and gray-zone coverage

    /**
     * Variance exactly equal to STATIONARY_VARIANCE_CEILING (0.04): the stationary
     * check is strict-less-than, so this value falls through to VEHICULAR.
     */
    @Test
    fun classify_varianceExactlyAtStationaryCeiling_isVehicularNotStationary() {
        val (cls, _) = ImuMotionClassifier.classify(variance = 0.04f, stepRate = 0f, hasAccelData = true)
        assertEquals(ImuClassification.VEHICULAR, cls)
    }

    /**
     * Variance in the gray zone between VEHICULAR_VARIANCE_FLOOR (0.03) and
     * STATIONARY_VARIANCE_CEILING (0.04): the stationary check runs first, so
     * minor vibrations above the vehicular floor are still classified STATIONARY.
     */
    @Test
    fun classify_varianceInGrayZoneBetweenThresholds_returnsStationary() {
        // 0.035 is above VEHICULAR_FLOOR (0.03) but below STATIONARY_CEILING (0.04).
        // Stationary check passes first → STATIONARY, not VEHICULAR.
        val (cls, _) = ImuMotionClassifier.classify(variance = 0.035f, stepRate = 0f, hasAccelData = true)
        assertEquals(ImuClassification.STATIONARY, cls)
    }

    /**
     * Steps above the pedestrian minimum with no accel window: the step check
     * runs before the hasAccelData gate, so PEDESTRIAN is still returned even
     * when the variance window is empty.
     */
    @Test
    fun classify_pedestrianStepRate_noAccelData_stillReturnsPedestrian() {
        val (cls, _) = ImuMotionClassifier.classify(variance = 0f, stepRate = 40f, hasAccelData = false)
        assertEquals(ImuClassification.PEDESTRIAN, cls)
    }

    /**
     * Confidence at exactly PEDESTRIAN_STEP_RATE_MIN / PEDESTRIAN_STEP_RATE_FULL_CONFIDENCE = 0.5.
     */
    @Test
    fun classify_pedestrianStepRateAtMinimum_confidenceIsHalf() {
        val (cls, confidence) = ImuMotionClassifier.classify(variance = 0f, stepRate = 40f, hasAccelData = true)
        assertEquals(ImuClassification.PEDESTRIAN, cls)
        assertEquals(0.5f, confidence, 0.001f)
    }

    /**
     * Confidence at exactly PEDESTRIAN_STEP_RATE_FULL_CONFIDENCE (80 steps/min) = 1.0.
     */
    @Test
    fun classify_pedestrianStepRateAtFullConfidence_confidenceIsOne() {
        val (cls, confidence) = ImuMotionClassifier.classify(variance = 0f, stepRate = 80f, hasAccelData = true)
        assertEquals(ImuClassification.PEDESTRIAN, cls)
        assertEquals(1.0f, confidence, 0.001f)
    }

    /**
     * Very high vehicular variance: confidence must be capped at 1.0.
     */
    @Test
    fun classify_vehicularVeryHighVariance_confidenceCappedAtOne() {
        val (cls, confidence) = ImuMotionClassifier.classify(variance = 100f, stepRate = 0f, hasAccelData = true)
        assertEquals(ImuClassification.VEHICULAR, cls)
        assertEquals(1.0f, confidence, 0.001f)
    }

    /**
     * Exact zero step rate and variance below VEHICULAR_VARIANCE_FLOOR but no accel data:
     * hasAccelData=false short-circuits to UNKNOWN. Step check runs first but step rate is 0
     * (below PEDESTRIAN_MIN=40), so it is skipped.
     */
    @Test
    fun classify_zeroStepsNoAccelData_returnsUnknown() {
        val (cls, confidence) = ImuMotionClassifier.classify(variance = 0f, stepRate = 0f, hasAccelData = false)
        assertEquals(ImuClassification.UNKNOWN, cls)
        assertEquals(0.0f, confidence, 0.001f)
    }

    // endregion
}
