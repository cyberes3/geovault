package com.geovault.tracker

import android.location.Location
import com.geovault.tracker.location.LowAccuracyFallbackCoordinator
import com.geovault.tracker.pipeline.TrackPointQuality
import com.geovault.tracker.pipeline.TrackPointRejectReason
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.settings.TrackerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackingServiceFallbackBehaviorTest {
    private fun location(
        lat: Double,
        lon: Double,
        timeMs: Long,
        accuracyMeters: Float? = null
    ): Location {
        return Location("gps").apply {
            latitude = lat
            longitude = lon
            time = timeMs
            if (accuracyMeters != null) {
                accuracy = accuracyMeters
            }
        }
    }

    @Test
    fun timeoutMs_clampsAndConvertsToMilliseconds() {
        assertEquals(
            TrackerSettings.MIN_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC * 1000L,
            TrackingService.resolveLowAccuracyFallbackTimeoutMs(0L)
        )
        assertEquals(
            60_000L,
            TrackingService.resolveLowAccuracyFallbackTimeoutMs(60L)
        )
        assertEquals(
            TrackerSettings.MAX_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC * 1000L,
            TrackingService.resolveLowAccuracyFallbackTimeoutMs(999_999L)
        )
    }

    @Test
    fun rejectedFix_thenAcceptedBeforeTimeout_cancelsFallbackEmission() {
        val coordinator = LowAccuracyFallbackCoordinator()
        val candidate = location(
            lat = 38.9,
            lon = -104.8,
            timeMs = 1_800_000_000_000L,
            accuracyMeters = 120f
        )

        val shouldArm = coordinator.onRejectedFixForLock(
            fallbackEligible = true,
            candidateLatitude = candidate.latitude,
            candidateLongitude = candidate.longitude,
            candidateTimestampMs = candidate.time
        )
        assertTrue(shouldArm)

        coordinator.onAcceptedFix()
        assertFalse(
            coordinator.shouldEmitFallback(
                fallbackEligible = true,
                hasCandidate = true
            )
        )
    }

    @Test
    fun timeoutWhileAwaitingLock_emitsAndStaysArmedForRepeatCycles() {
        val coordinator = LowAccuracyFallbackCoordinator()
        val firstCandidate = location(
            lat = 38.9,
            lon = -104.8,
            timeMs = 1_800_000_000_000L,
            accuracyMeters = 120f
        )
        val secondCandidate = location(
            lat = 38.9001,
            lon = -104.7999,
            timeMs = 1_800_000_030_000L,
            accuracyMeters = 130f
        )

        assertTrue(
            coordinator.onRejectedFixForLock(
                fallbackEligible = true,
                candidateLatitude = firstCandidate.latitude,
                candidateLongitude = firstCandidate.longitude,
                candidateTimestampMs = firstCandidate.time
            )
        )
        assertTrue(coordinator.shouldEmitFallback(fallbackEligible = true, hasCandidate = true))
        coordinator.onFallbackEmitted(
            candidateLatitude = firstCandidate.latitude,
            candidateLongitude = firstCandidate.longitude,
            candidateTimestampMs = firstCandidate.time
        )
        // Service loop should not emit the exact same stale fallback repeatedly.
        assertFalse(coordinator.shouldEmitFallback(fallbackEligible = true, hasCandidate = true))
        // Service loop should emit again once a materially newer candidate arrives.
        assertFalse(
            coordinator.onRejectedFixForLock(
                fallbackEligible = true,
                candidateLatitude = secondCandidate.latitude,
                candidateLongitude = secondCandidate.longitude,
                candidateTimestampMs = secondCandidate.time
            )
        )
        assertTrue(coordinator.shouldEmitFallback(fallbackEligible = true, hasCandidate = true))
    }

    @Test
    fun disabledFallback_neverArmsOrEmits() {
        val coordinator = LowAccuracyFallbackCoordinator()
        val candidate = location(
            lat = 38.9,
            lon = -104.8,
            timeMs = 1_800_000_000_000L,
            accuracyMeters = 120f
        )

        assertFalse(
            coordinator.onRejectedFixForLock(
                fallbackEligible = false,
                candidateLatitude = candidate.latitude,
                candidateLongitude = candidate.longitude,
                candidateTimestampMs = candidate.time
            )
        )
        assertFalse(
            coordinator.shouldEmitFallback(
                fallbackEligible = false,
                hasCandidate = true
            )
        )
    }

    @Test
    fun shouldStartFastGpsLock_startsForBadAccuracyOverThresholdOrMissingAccuracy() {
        assertTrue(
            TrackingService.shouldStartFastGpsLock(
                rejectReason = TrackPointRejectReason.BAD_ACCURACY,
                measuredAccuracyMeters = 80f,
                accuracyFilterMeters = 50f
            )
        )
        assertTrue(
            TrackingService.shouldStartFastGpsLock(
                rejectReason = TrackPointRejectReason.BAD_ACCURACY,
                measuredAccuracyMeters = null,
                accuracyFilterMeters = 50f
            )
        )
        assertFalse(
            TrackingService.shouldStartFastGpsLock(
                rejectReason = TrackPointRejectReason.STALE,
                measuredAccuracyMeters = 80f,
                accuracyFilterMeters = 50f
            )
        )
        assertTrue(
            TrackingService.shouldStartFastGpsLock(
                rejectReason = null,
                measuredAccuracyMeters = null,
                accuracyFilterMeters = 50f
            )
        )
        assertFalse(
            TrackingService.shouldStartFastGpsLock(
                rejectReason = TrackPointRejectReason.BAD_ACCURACY,
                measuredAccuracyMeters = 40f,
                accuracyFilterMeters = 50f
            )
        )
    }

    @Test
    fun successfulBatchSentCount_matchesSuccessfulUploadBatchSize() {
        assertEquals(0, TrackingService.successfulBatchSentCount(0))
        assertEquals(1, TrackingService.successfulBatchSentCount(1))
        assertEquals(50, TrackingService.successfulBatchSentCount(50))
    }

    @Test
    fun successfulBatchSentCount_neverReturnsNegative() {
        assertEquals(0, TrackingService.successfulBatchSentCount(-1))
        assertEquals(0, TrackingService.successfulBatchSentCount(Int.MIN_VALUE))
    }

    @Test
    fun visibleSentCountForBatchIds_countsOnlyCurrentSessionRows() {
        val count = TrackingService.visibleSentCountForBatchIds(
            batchIds = listOf(9L, 10L, 11L, 12L),
            sessionBoundaryId = 9L
        )
        assertEquals(3, count)
    }

    @Test
    fun visibleSentCountForBatchIds_excludesBacklogRowsFromSentMetrics() {
        val count = TrackingService.visibleSentCountForBatchIds(
            batchIds = listOf(1L, 2L, 3L),
            sessionBoundaryId = 10L
        )
        assertEquals(0, count)
    }

    @Test
    fun hasRecoveredFastGpsLock_requiresHighConfidenceAndAccuracyAtOrBelowFilter() {
        assertTrue(
            TrackingService.hasRecoveredFastGpsLock(
                quality = TrackPointQuality.HIGH_CONFIDENCE,
                measuredAccuracyMeters = 15f,
                accuracyFilterMeters = 20f
            )
        )
        assertFalse(
            TrackingService.hasRecoveredFastGpsLock(
                quality = TrackPointQuality.DEGRADED,
                measuredAccuracyMeters = 10f,
                accuracyFilterMeters = 20f
            )
        )
        assertFalse(
            TrackingService.hasRecoveredFastGpsLock(
                quality = TrackPointQuality.HIGH_CONFIDENCE,
                measuredAccuracyMeters = null,
                accuracyFilterMeters = 20f
            )
        )
        assertFalse(
            TrackingService.hasRecoveredFastGpsLock(
                quality = TrackPointQuality.HIGH_CONFIDENCE,
                measuredAccuracyMeters = 25f,
                accuracyFilterMeters = 20f
            )
        )
    }

    @Test
    fun computeElasticitySpeedBucket_quantizesInFiveMpsSteps() {
        assertEquals(0, TrackingService.computeElasticitySpeedBucket(null))
        assertEquals(0, TrackingService.computeElasticitySpeedBucket(0f))
        assertEquals(0, TrackingService.computeElasticitySpeedBucket(4.9f))
        assertEquals(1, TrackingService.computeElasticitySpeedBucket(5.0f))
        assertEquals(2, TrackingService.computeElasticitySpeedBucket(10.0f))
    }

    @Test
    fun computeElasticDistanceFilterMeters_scalesFromBaseAndCapsAtSettingsMax() {
        assertEquals(
            30f,
            TrackingService.computeElasticDistanceFilterMeters(baseDistanceMeters = 30f, speedBucket = 0)
        )
        assertEquals(
            40.5f,
            TrackingService.computeElasticDistanceFilterMeters(baseDistanceMeters = 30f, speedBucket = 1)
        )
        assertEquals(
            TrackerSettings.MAX_DISTANCE_FILTER_METERS,
            TrackingService.computeElasticDistanceFilterMeters(
                baseDistanceMeters = TrackerSettings.MAX_DISTANCE_FILTER_METERS,
                speedBucket = 8
            )
        )
    }

    @Test
    fun computeElasticityModeBoundBucket_capsWalkingButNotDriving() {
        assertEquals(
            2,
            TrackingService.computeElasticityModeBoundBucket(
                speedBucket = 5,
                motionMode = TrackingMotionMode.WALKING
            )
        )
        assertEquals(
            5,
            TrackingService.computeElasticityModeBoundBucket(
                speedBucket = 5,
                motionMode = TrackingMotionMode.DRIVING
            )
        )
    }

    @Test
    fun resolveLocalGpsPolicyOverrides_onlyTightensWalking() {
        val walking = TrackingService.resolveLocalGpsPolicyOverrides(TrackingMotionMode.WALKING)
        assertEquals(140.0, walking.maxBurstDistanceMeters)
        assertEquals(8.0, walking.burstWindowSeconds)
        assertEquals(1.15, walking.outlierDistanceMultiplier)
        assertEquals(2.0, walking.rollingDistanceMultiplier)

        val biking = TrackingService.resolveLocalGpsPolicyOverrides(TrackingMotionMode.BIKING)
        assertEquals(null, biking.maxBurstDistanceMeters)
        assertEquals(null, biking.burstWindowSeconds)
        assertEquals(null, biking.outlierDistanceMultiplier)
        assertEquals(null, biking.rollingDistanceMultiplier)
    }

    @Test
    fun computeNormalRequestMaxDelayMs_scalesWithIntervalAndRespectsCeiling() {
        assertEquals(3_000L, TrackingService.computeNormalRequestMaxDelayMs(intervalSec = 1L))
        assertEquals(45_000L, TrackingService.computeNormalRequestMaxDelayMs(intervalSec = 15L))
        assertEquals(60_000L, TrackingService.computeNormalRequestMaxDelayMs(intervalSec = 60L))
    }

    @Test
    fun selectPreferredFastGpsSample_prefersValidAccurateOverInvalid() {
        val nowMs = 1_800_000_100_000L
        val invalid = location(
            lat = 38.9,
            lon = -104.8,
            timeMs = nowMs - 1_000L,
            accuracyMeters = null
        )
        val valid = location(
            lat = 38.9001,
            lon = -104.7999,
            timeMs = nowMs - 2_000L,
            accuracyMeters = 25f
        )
        val selected = TrackingService.selectPreferredFastGpsSample(
            currentBest = invalid,
            candidate = valid,
            desiredAccuracyMeters = 50f,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 0L
        )
        assertEquals(valid, selected)
    }

    @Test
    fun selectPreferredFastGpsSample_prefersDesiredAccuracyWhenAgesSimilar() {
        val nowMs = 1_800_000_100_000L
        val current = location(
            lat = 38.9,
            lon = -104.8,
            timeMs = nowMs - 3_000L,
            accuracyMeters = 70f
        )
        val candidate = location(
            lat = 38.9001,
            lon = -104.7999,
            timeMs = nowMs - 2_500L,
            accuracyMeters = 40f
        )
        val selected = TrackingService.selectPreferredFastGpsSample(
            currentBest = current,
            candidate = candidate,
            desiredAccuracyMeters = 50f,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 0L
        )
        assertEquals(candidate, selected)
    }

    @Test
    fun selectPreferredFastGpsSample_prefersNewerWhenAccuracySimilarAndAgeGapLarge() {
        val nowMs = 1_800_000_100_000L
        val oldAccurate = location(
            lat = 38.9,
            lon = -104.8,
            timeMs = nowMs - 90_000L,
            accuracyMeters = 40f
        )
        val newerSlightlyWorse = location(
            lat = 38.9002,
            lon = -104.7998,
            timeMs = nowMs - 5_000L,
            accuracyMeters = 45f
        )
        val selected = TrackingService.selectPreferredFastGpsSample(
            currentBest = oldAccurate,
            candidate = newerSlightlyWorse,
            desiredAccuracyMeters = 50f,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 0L
        )
        assertEquals(newerSlightlyWorse, selected)
    }

    @Test
    fun shouldEmitFallbackForTransition_rejectsImplausibleJump() {
        val previous = location(
            lat = 38.9000,
            lon = -104.8000,
            timeMs = 1_800_000_000_000L,
            accuracyMeters = 10f
        )
        val candidate = location(
            lat = 38.9055,
            lon = -104.7950,
            timeMs = 1_800_000_010_000L,
            accuracyMeters = 180f
        )
        val result = TrackingService.shouldEmitFallbackForTransition(
            previousAcceptedLocation = previous,
            fallbackCandidateLocation = candidate,
            nowMs = candidate.time
        )
        assertFalse(result)
    }

    @Test
    fun shouldEmitFallbackForTransition_acceptsPlausibleNearbyFix() {
        val previous = location(
            lat = 38.9000,
            lon = -104.8000,
            timeMs = 1_800_000_000_000L,
            accuracyMeters = 10f
        )
        val candidate = location(
            lat = 38.9005,
            lon = -104.7997,
            timeMs = 1_800_000_060_000L,
            accuracyMeters = 150f
        )
        val result = TrackingService.shouldEmitFallbackForTransition(
            previousAcceptedLocation = previous,
            fallbackCandidateLocation = candidate,
            nowMs = candidate.time
        )
        assertTrue(result)
    }
}
