package com.geovault.tracker.services

import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.location.StationaryPingController
import com.geovault.tracker.policy.filter.MotionProfileTuning
import com.geovault.tracker.settings.TrackerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PositioningDensityTest {
    @Test
    fun from_sparseTrackingFlag_selectsDensity() {
        assertEquals(PositioningDensity.Normal, PositioningDensity.from(false))
        assertEquals(PositioningDensity.Sparse, PositioningDensity.from(true))
        assertEquals(PositioningDensity.Sparse, PositioningDensity.from(TrackerSettings(sparseTracking = true)))
    }

    @Test
    fun normalScaling_isIdentity() {
        val density = PositioningDensity.Normal
        assertEquals(20L, density.scaleIntervalSec(20L))
        assertEquals(7f, density.scaleDistanceMeters(7f))
        assertEquals(300_000L, density.scaleDurationMs(300_000L))
    }

    @Test
    fun sparse_doublesWalkingCadence() {
        val walking = PositioningPresets.forMotionMode(
            TrackingMotionMode.WALKING,
            PositioningDensity.Sparse,
        )
        assertEquals(TrackingLocationPolicy.WALKING_INTERVAL_SEC * 2, walking.locationIntervalSec)
        assertEquals(TrackingLocationPolicy.WALKING_DISTANCE_FILTER_METERS * 2f, walking.distanceFilterMeters)
    }

    @Test
    fun sparse_doublesBikingCadence() {
        val biking = PositioningPresets.forMotionMode(
            TrackingMotionMode.BIKING,
            PositioningDensity.Sparse,
        )
        assertEquals(TrackingLocationPolicy.BIKING_INTERVAL_SEC * 2, biking.locationIntervalSec)
        assertEquals(TrackingLocationPolicy.BIKING_DISTANCE_FILTER_METERS * 2f, biking.distanceFilterMeters)
    }

    @Test
    fun sparse_doublesDrivingCadence() {
        val driving = PositioningPresets.forMotionMode(
            TrackingMotionMode.DRIVING,
            PositioningDensity.Sparse,
        )
        assertEquals(TrackingLocationPolicy.DRIVING_INTERVAL_SEC * 2, driving.locationIntervalSec)
        assertEquals(TrackingLocationPolicy.DRIVING_DISTANCE_FILTER_METERS * 2f, driving.distanceFilterMeters)
    }

    @Test
    fun normal_leavesPresetUnchanged() {
        val biking = PositioningPresets.forMotionMode(TrackingMotionMode.BIKING)
        assertEquals(TrackingLocationPolicy.BIKING_INTERVAL_SEC, biking.locationIntervalSec)
        assertEquals(TrackingLocationPolicy.BIKING_DISTANCE_FILTER_METERS, biking.distanceFilterMeters)
    }

    @Test
    fun withDensity_normalReturnsSameInstance() {
        val base = PositioningPresets.forMotionMode(TrackingMotionMode.WALKING)
        assertSame(base, base.withDensity(PositioningDensity.Normal))
    }

    @Test
    fun withDensity_sparsePreservesFilterPhysicsAndAccuracy() {
        val base = PositioningPresets.forMotionMode(TrackingMotionMode.DRIVING)
        val sparse = base.withDensity(PositioningDensity.Sparse)

        assertEquals(base.motionMode, sparse.motionMode)
        assertEquals(base.filterTuning, sparse.filterTuning)
        assertEquals(base.recoverySpeedCapMps, sparse.recoverySpeedCapMps)
        assertEquals(base.accuracyThresholdMeters, sparse.accuracyThresholdMeters)
        assertNotEquals(base.locationIntervalSec, sparse.locationIntervalSec)
        assertNotEquals(base.distanceFilterMeters, sparse.distanceFilterMeters)
    }

    @Test
    fun sparse_doublesStationaryProbeDuration() {
        val scaledMs = PositioningDensity.Sparse.scaleDurationMs(StationaryPingController.DEFAULT_INTERVAL_MS)
        assertEquals(StationaryPingController.DEFAULT_INTERVAL_MS * 2, scaledMs)
    }

    @Test
    fun scaleIntervalSec_oneSecondBase_doublesToTwoWhenSparse() {
        assertEquals(2L, PositioningDensity.Sparse.scaleIntervalSec(1L))
    }

    @Test
    fun scaleDurationMs_oneMillisecondBase_doublesWhenSparse() {
        assertEquals(2L, PositioningDensity.Sparse.scaleDurationMs(1L))
    }

    @Test
    fun defaultForMotionMode_usesNormalDensity() {
        val explicitNormal = PositioningPresets.forMotionMode(
            TrackingMotionMode.WALKING,
            PositioningDensity.Normal,
        )
        val defaultDensity = PositioningPresets.forMotionMode(TrackingMotionMode.WALKING)
        assertEquals(explicitNormal, defaultDensity)
    }

    @Test
    fun sparse_recoveryConfigStillUsesMotionSpeedCap() {
        val sparseWalking = PositioningPresets.forMotionMode(
            TrackingMotionMode.WALKING,
            PositioningDensity.Sparse,
        )
        assertEquals(
            MotionProfileTuning.Walking.maxImpliedSpeedMps.toFloat(),
            sparseWalking.recoveryConfig(maxLocalPointGapMs = 90_000L).recoverySpeedCapMps,
        )
    }
}
