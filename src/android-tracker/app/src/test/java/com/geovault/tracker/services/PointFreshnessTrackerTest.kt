package com.geovault.tracker.services

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
