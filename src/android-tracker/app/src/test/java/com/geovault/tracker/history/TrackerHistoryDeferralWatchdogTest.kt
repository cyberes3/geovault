package com.geovault.tracker.history

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerHistoryDeferralWatchdogTest {
    private val key = TrackerHistoryKey("tracker-1", TrackerHistoryWindow("1h"))
    private val otherKey = TrackerHistoryKey("tracker-2", TrackerHistoryWindow("1h"))

    @Test
    fun doesNotForceCommitBelowThreshold() {
        val sut = TrackerHistoryDeferralWatchdog()
        repeat(TrackerHistoryDeferralWatchdog.FORCE_COMMIT_AFTER - 1) { sut.onDeferred(key) }
        assertFalse(sut.shouldForceCommit(key))
    }

    @Test
    fun forcesCommitAfterConsecutiveDeferralsReachThreshold() {
        val sut = TrackerHistoryDeferralWatchdog()
        repeat(TrackerHistoryDeferralWatchdog.FORCE_COMMIT_AFTER) { sut.onDeferred(key) }
        assertTrue(sut.shouldForceCommit(key))
    }

    @Test
    fun onCommittedResetsCountForThatKeyOnly() {
        val sut = TrackerHistoryDeferralWatchdog()
        repeat(TrackerHistoryDeferralWatchdog.FORCE_COMMIT_AFTER) { sut.onDeferred(key) }
        repeat(TrackerHistoryDeferralWatchdog.FORCE_COMMIT_AFTER) { sut.onDeferred(otherKey) }
        sut.onCommitted(key)
        assertFalse(sut.shouldForceCommit(key))
        assertTrue(sut.shouldForceCommit(otherKey))
    }

    @Test
    fun forgetClearsAllWindowsForTracker() {
        val sut = TrackerHistoryDeferralWatchdog()
        val sessionKey = TrackerHistoryKey("tracker-1", TrackerHistoryWindow("session"))
        repeat(TrackerHistoryDeferralWatchdog.FORCE_COMMIT_AFTER) { sut.onDeferred(key) }
        repeat(TrackerHistoryDeferralWatchdog.FORCE_COMMIT_AFTER) { sut.onDeferred(sessionKey) }
        sut.forget("tracker-1")
        assertFalse(sut.shouldForceCommit(key))
        assertFalse(sut.shouldForceCommit(sessionKey))
    }

    @Test
    fun resetClearsEveryKey() {
        val sut = TrackerHistoryDeferralWatchdog()
        repeat(TrackerHistoryDeferralWatchdog.FORCE_COMMIT_AFTER) { sut.onDeferred(key) }
        sut.reset()
        assertFalse(sut.shouldForceCommit(key))
    }
}
