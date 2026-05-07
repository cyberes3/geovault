package com.geovault.tracker.policy.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KalmanFilterTest {

    @Test
    fun firstUpdate_initialisesStateToMeasurement() {
        val filter = KalmanFilter()
        val out = filter.update(measurement = 12.5, accuracyMeters = 4.0)
        assertEquals(12.5, out, 1e-6)
    }

    @Test
    fun convergesToSteadyMeasurement() {
        val filter = KalmanFilter(KalmanTuning.forProfile(KalmanProfile.Default))
        repeat(15) { filter.update(measurement = 10.0, accuracyMeters = 3.0) }
        val out = filter.update(measurement = 10.0, accuracyMeters = 3.0)
        assertTrue(abs(out - 10.0) < 0.5)
    }

    @Test
    fun adaptsRUpForLargeInnovation() {
        val filter = KalmanFilter()
        filter.update(measurement = 5.0, accuracyMeters = 4.0)
        val rBefore = filter.measurementNoise
        filter.update(measurement = 250.0, accuracyMeters = 4.0)
        val rAfter = filter.measurementNoise
        assertTrue("R should increase after a high-NIS observation", rAfter >= rBefore)
    }

    @Test
    fun adaptsRDownForCleanInnovations() {
        val filter = KalmanFilter()
        repeat(20) { filter.update(measurement = 4.0, accuracyMeters = 3.0) }
        val rSettled = filter.measurementNoise
        val tuningR = KalmanTuning.forProfile(KalmanProfile.Default).r
        assertTrue("R should never escape its half-prior floor", rSettled >= tuningR * 0.5 - 1e-9)
        assertTrue("R should never escape its 8x ceiling", rSettled <= tuningR * 8.0 + 1e-9)
    }

    @Test
    fun configureForSpeed_changesQ_inExpectedDirection() {
        val filter = KalmanFilter()
        filter.configureForSpeed(0.0)
        val qStill = filter.processNoise
        filter.configureForSpeed(15.0)
        val qDriving = filter.processNoise
        assertNotEquals(qStill, qDriving)
        assertTrue("driving Q must be looser than standstill Q", qDriving > qStill)
    }

    @Test
    fun reset_returnsFilterToInitialTuning() {
        val tuning = KalmanTuning.forProfile(KalmanProfile.Aggressive)
        val filter = KalmanFilter(tuning)
        repeat(5) { filter.update(measurement = 100.0, accuracyMeters = 12.0) }
        filter.reset()
        assertEquals(tuning.x0, filter.state, 1e-9)
        assertEquals(tuning.p0, filter.covariance, 1e-9)
        assertEquals(tuning.q, filter.processNoise, 1e-9)
        assertEquals(tuning.r, filter.measurementNoise, 1e-9)
    }

    @Test
    fun forProfile_distinctTuningPresets() {
        val def = KalmanTuning.forProfile(KalmanProfile.Default)
        val aggressive = KalmanTuning.forProfile(KalmanProfile.Aggressive)
        val conservative = KalmanTuning.forProfile(KalmanProfile.Conservative)
        assertTrue(aggressive.q < def.q)
        assertTrue(conservative.q > def.q)
    }
}
