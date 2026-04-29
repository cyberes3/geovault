package com.geovault.tracker.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveButDeadTrackerPolicyTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun isActiveButDead_true_whenMetadataRecentAndDataStaleAndRowNewerThanData() {
        val now = t0
        val lastData = now - 20L * 60L * 1000L
        val updated = now - 5L * 60L * 1000L
        assertTrue(ActiveButDeadTrackerPolicy.isActiveButDead(now, updated, lastData))
    }

    @Test
    fun isActiveButDead_false_whenDataFresh() {
        val now = t0
        val lastData = now - 5L * 60L * 1000L
        val updated = now - 3L * 60L * 1000L
        assertFalse(ActiveButDeadTrackerPolicy.isActiveButDead(now, updated, lastData))
    }

    @Test
    fun isActiveButDead_false_whenMetadataNotRecent() {
        val now = t0
        val lastData = now - 20L * 60L * 1000L
        val updated = now - 4L * 60L * 60L * 1000L
        assertFalse(ActiveButDeadTrackerPolicy.isActiveButDead(now, updated, lastData))
    }

    @Test
    fun isActiveButDead_false_whenLastDataNull() {
        val now = t0
        assertFalse(ActiveButDeadTrackerPolicy.isActiveButDead(now, now - 1000L, null))
    }

    @Test
    fun isActiveButDead_false_whenIdleDataAndRowAgedTogether() {
        val now = t0
        val lastData = now - 20L * 60L * 1000L
        val updated = lastData
        assertFalse(ActiveButDeadTrackerPolicy.isActiveButDead(now, updated, lastData))
    }
}
