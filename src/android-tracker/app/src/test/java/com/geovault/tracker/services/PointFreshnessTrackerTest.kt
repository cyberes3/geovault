package com.geovault.tracker.services

import com.geovault.tracker.TrackingLocationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointFreshnessTrackerTest {

    @Test
    fun noLocalPoint_afterDeadline_forcesRecovery() {
        val tracker = PointFreshnessTracker()
        tracker.reset(sessionStartedAtMs = 1_000L)

        assertTrue(tracker.shouldForceLocalRecovery(nowMs = 91_001L, intervalSec = 15L))
    }

    @Test
    fun recentLocalPoint_isFreshAndDoesNotForceRecovery() {
        val tracker = PointFreshnessTracker()
        tracker.reset(sessionStartedAtMs = 1_000L)
        tracker.markLocalPointPersisted(nowMs = 10_000L)

        assertTrue(tracker.isLocalFresh(nowMs = 40_000L, intervalSec = 15L))
        assertFalse(tracker.shouldForceLocalRecovery(nowMs = 40_000L, intervalSec = 15L))
    }

    @Test
    fun seedLocalPointPersistedAt_restoresWithoutAdvancingInternalAccepted() {
        val tracker = PointFreshnessTracker()
        tracker.reset(sessionStartedAtMs = 1_000L)
        tracker.markInternalAccepted(nowMs = 5_000L)
        tracker.seedLocalPointPersistedAt(120_000L)

        assertEquals(120_000L, tracker.lastLocalPointPersistedAtMs)
        assertTrue(tracker.isLocalFresh(nowMs = 150_000L, intervalSec = 15L))
        assertFalse(tracker.shouldForceLocalRecovery(nowMs = 150_000L, intervalSec = 15L))
    }

    @Test
    fun maxAllowedPointGap_normalWalkingInterval_hitsMinimumGap() {
        val gapMs = PointFreshnessTracker.maxAllowedPointGapMsForInterval(
            TrackingLocationPolicy.WALKING_INTERVAL_SEC,
        )
        assertEquals(60_000L, gapMs)
    }

    @Test
    fun maxAllowedPointGap_sparseWalkingInterval_staysCappedAtNinetySeconds() {
        val sparseIntervalSec = PositioningDensity.Sparse.scaleIntervalSec(
            TrackingLocationPolicy.WALKING_INTERVAL_SEC,
        )
        val gapMs = PointFreshnessTracker.maxAllowedPointGapMsForInterval(sparseIntervalSec)
        assertEquals(90_000L, gapMs)
    }

    @Test
    fun shouldForceLocalRecovery_sparseWalkingInterval_usesScaledDeadline() {
        val tracker = PointFreshnessTracker()
        tracker.reset(sessionStartedAtMs = 1_000L)
        val sparseIntervalSec = PositioningDensity.Sparse.scaleIntervalSec(
            TrackingLocationPolicy.WALKING_INTERVAL_SEC,
        )
        val deadlineMs = PointFreshnessTracker.maxAllowedPointGapMsForInterval(sparseIntervalSec)

        assertFalse(tracker.shouldForceLocalRecovery(nowMs = 1_000L + deadlineMs, intervalSec = sparseIntervalSec))
        assertTrue(tracker.shouldForceLocalRecovery(nowMs = 1_001L + deadlineMs, intervalSec = sparseIntervalSec))
    }

    @Test
    fun uploadFreshness_isTrackedSeparatelyFromLocalFreshness() {
        val tracker = PointFreshnessTracker()
        tracker.reset(sessionStartedAtMs = 1_000L)
        tracker.markLocalPointPersisted(nowMs = 10_000L)
        tracker.markUploadSucceeded(nowMs = 20_000L)

        assertTrue(tracker.isLocalFresh(nowMs = 30_000L, intervalSec = 15L))
        assertFalse(tracker.shouldForceLocalRecovery(nowMs = 30_000L, intervalSec = 15L))
        assertEquals(10_000L, tracker.uploadAgeMs(nowMs = 30_000L))
    }
}
