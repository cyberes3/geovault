package com.geovault.tracker.positioning

import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.location.StationaryPingController
import com.geovault.tracker.policy.filter.MotionProfileTuning
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.settings.TrackerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PositioningContextTest {
    @Test
    fun build_autoModeUsesMotionProfileCadenceAndRecoveryConfig() {
        val context = PositioningContext.build(
            settings = TrackerSettings(),
            activeMotionMode = TrackingMotionMode.DRIVING,
            effectiveDistanceFilterMeters = 123f,
            collectionPace = RecordingPace.Moving,
            localPointMaxGapMs = 90_000L,
        )

        assertEquals(TrackingLocationPolicy.DRIVING_INTERVAL_SEC, context.locationIntervalSec)
        assertEquals(123f, context.distanceFilterMeters)
        assertEquals(TrackingLocationPolicy.DRIVING_INTERVAL_SEC, context.pointFreshnessIntervalSec)
        assertEquals(90_000L, context.recoveryConfig.maxLocalPointGapMs)
        assertEquals(60f, context.recoveryConfig.recoverySpeedCapMps)
    }

    @Test
    fun build_alwaysUsesPresetCadenceAndAccuracyForPhysics() {
        val context = PositioningContext.build(
            settings = TrackerSettings(
                accuracyFilterMeters = 33f,
            ),
            activeMotionMode = TrackingMotionMode.WALKING,
            effectiveDistanceFilterMeters = 8f,
            collectionPace = RecordingPace.Moving,
            localPointMaxGapMs = 120_000L,
        )

        assertEquals(TrackingLocationPolicy.WALKING_INTERVAL_SEC, context.locationIntervalSec)
        assertEquals(TrackingLocationPolicy.WALKING_INTERVAL_SEC, context.pointFreshnessIntervalSec)
        assertEquals(TrackerSettings.INTERNAL_ACCURACY_FILTER_METERS, context.effectiveAccuracyThresholdMeters)
        assertEquals(4.5f, context.recoveryConfig.recoverySpeedCapMps)
    }

    @Test
    fun build_internalAccuracyThresholdFeedsFilterConfig() {
        val context = PositioningContext.build(
            settings = TrackerSettings(
                accuracyFilterMeters = 33f,
            ),
            activeMotionMode = TrackingMotionMode.WALKING,
            effectiveDistanceFilterMeters = 7f,
            collectionPace = RecordingPace.Moving,
            localPointMaxGapMs = 120_000L,
        )

        assertEquals(TrackingLocationPolicy.WALKING_INTERVAL_SEC, context.locationIntervalSec)
        assertEquals(TrackingLocationPolicy.WALKING_INTERVAL_SEC, context.pointFreshnessIntervalSec)
        assertEquals(TrackerSettings.INTERNAL_ACCURACY_FILTER_METERS, context.effectiveAccuracyThresholdMeters)
        assertEquals(TrackerSettings.INTERNAL_ACCURACY_FILTER_METERS.toDouble(), context.filterConfig.trackingAccuracyThresholdMeters, 0.0)
    }

    @Test
    fun build_sparseTrackingFalse_matchesNormalCadence() {
        val sparseOff = PositioningContext.build(
            settings = TrackerSettings(sparseTracking = false),
            activeMotionMode = TrackingMotionMode.BIKING,
            effectiveDistanceFilterMeters = 30f,
            collectionPace = RecordingPace.Moving,
            localPointMaxGapMs = 90_000L,
        )
        val defaultSettings = PositioningContext.build(
            settings = TrackerSettings(),
            activeMotionMode = TrackingMotionMode.BIKING,
            effectiveDistanceFilterMeters = 30f,
            collectionPace = RecordingPace.Moving,
            localPointMaxGapMs = 90_000L,
        )

        assertEquals(defaultSettings.locationIntervalSec, sparseOff.locationIntervalSec)
        assertEquals(defaultSettings.stationaryProbeIntervalMs, sparseOff.stationaryProbeIntervalMs)
    }

    @Test
    fun build_sparseTrackingDoublesCadenceAndProbeInterval() {
        val context = PositioningContext.build(
            settings = TrackerSettings(sparseTracking = true),
            activeMotionMode = TrackingMotionMode.WALKING,
            effectiveDistanceFilterMeters = 14f,
            collectionPace = RecordingPace.Moving,
            localPointMaxGapMs = 90_000L,
        )

        assertEquals(TrackingLocationPolicy.WALKING_INTERVAL_SEC * 2, context.locationIntervalSec)
        assertEquals(TrackingLocationPolicy.WALKING_INTERVAL_SEC * 2, context.pointFreshnessIntervalSec)
        assertEquals(StationaryPingController.DEFAULT_INTERVAL_MS * 2, context.stationaryProbeIntervalMs)
        assertEquals(MotionProfileTuning.Walking.maxImpliedSpeedMps, context.filterConfig.maxImpliedSpeedMps, 0.0)
        assertEquals(TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS, context.stationaryRadiusMeters)
        assertEquals(TrackingLocationPolicy.STATIONARY_ACCURACY_CEILING_METERS, context.stationaryAccuracyCeilingMeters)
    }

    @Test
    fun build_sparseDrivingDoublesCadenceWithoutChangingFilterTuning() {
        val normal = PositioningContext.build(
            settings = TrackerSettings(),
            activeMotionMode = TrackingMotionMode.DRIVING,
            effectiveDistanceFilterMeters = 100f,
            collectionPace = RecordingPace.Moving,
            localPointMaxGapMs = 90_000L,
        )
        val sparse = PositioningContext.build(
            settings = TrackerSettings(sparseTracking = true),
            activeMotionMode = TrackingMotionMode.DRIVING,
            effectiveDistanceFilterMeters = 200f,
            collectionPace = RecordingPace.Moving,
            localPointMaxGapMs = 90_000L,
        )

        assertEquals(TrackingLocationPolicy.DRIVING_INTERVAL_SEC * 2, sparse.locationIntervalSec)
        assertEquals(200f, sparse.distanceFilterMeters)
        assertEquals(normal.filterConfig.maxImpliedSpeedMps, sparse.filterConfig.maxImpliedSpeedMps, 0.0)
        assertEquals(normal.filterConfig.maxBurstDistanceMeters, sparse.filterConfig.maxBurstDistanceMeters, 0.0)
        assertNotEquals(normal.stationaryProbeIntervalMs, sparse.stationaryProbeIntervalMs)
    }

    @Test
    fun build_effectiveDistanceFilterPassthroughIndependentOfSparse() {
        val context = PositioningContext.build(
            settings = TrackerSettings(sparseTracking = true),
            activeMotionMode = TrackingMotionMode.WALKING,
            effectiveDistanceFilterMeters = 42f,
            collectionPace = RecordingPace.Moving,
            localPointMaxGapMs = 90_000L,
        )
        assertEquals(42f, context.distanceFilterMeters)
    }

    @Test
    fun build_activeMotionModeSelectsFilterTuningValues() {
        val context = PositioningContext.build(
            settings = TrackerSettings(),
            activeMotionMode = TrackingMotionMode.BIKING,
            effectiveDistanceFilterMeters = 30f,
            collectionPace = RecordingPace.Moving,
            localPointMaxGapMs = 90_000L,
        )

        assertEquals(MotionProfileTuning.Biking.maxImpliedSpeedMps, context.filterConfig.maxImpliedSpeedMps, 0.0)
        assertEquals(MotionProfileTuning.Biking.maxBurstDistanceMeters, context.filterConfig.maxBurstDistanceMeters, 0.0)
        assertEquals(MotionProfileTuning.Biking.burstWindowSeconds, context.filterConfig.burstWindowSeconds, 0.0)
        assertEquals(MotionProfileTuning.Biking.movementCandidate, context.filterConfig.movementCandidate)
        assertEquals(MotionProfileTuning.Biking.speedRecovery, context.filterConfig.speedRecovery)
    }
}
