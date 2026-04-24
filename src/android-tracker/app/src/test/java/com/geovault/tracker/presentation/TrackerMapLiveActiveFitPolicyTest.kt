package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapLiveActiveFitPolicyTest {

    @Test
    fun visibility_singleSessionNoTrailPoints_hidden() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtimeRunning = false,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = false,
                isSelectedDefaultTracker = false,
            )
        )
        assertFalse(result.showButton)
    }

    @Test
    fun visibility_singleSessionWithTrailPoints_shown() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtimeRunning = false,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = true,
                isSelectedDefaultTracker = false,
            )
        )
        assertTrue(result.showButton)
        assertTrue(result.buttonEnabled)
    }

    @Test
    fun visibility_singleSessionDefaultTracker_hidden() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtimeRunning = false,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = true,
                isSelectedDefaultTracker = true,
            )
        )
        assertFalse(result.showButton)
    }

    @Test
    fun visibility_allQueueWithFollowArmed_shown() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                runtimeRunning = false,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = false,
                isSelectedDefaultTracker = false,
            )
        )
        assertTrue(result.showButton)
        assertTrue(result.buttonEnabled)
    }

    @Test
    fun visibility_groupWithFollowArmed_shown() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                runtimeRunning = false,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = false,
                isSelectedDefaultTracker = false,
            )
        )
        assertTrue(result.showButton)
    }

    @Test
    fun visibility_allQueueWithoutFollowArmed_hidden() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                runtimeRunning = false,
                followLockArmed = false,
                liveActiveFitEnabled = false,
                hasTrailPoints = false,
                isSelectedDefaultTracker = false,
            )
        )
        assertFalse(result.showButton)
    }

    @Test
    fun visibility_runtimeRunning_hidden() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                runtimeRunning = true,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = false,
                isSelectedDefaultTracker = false,
            )
        )
        assertFalse(result.showButton)
    }

    @Test
    fun filterActiveTrails_filtersOldTrackers() {
        val nowMs = System.currentTimeMillis()
        val recentTrail = listOf(makeQueuedLocation(nowMs - 60_000L))
        val staleTrail = listOf(makeQueuedLocation(nowMs - 20 * 60 * 1000L))
        val trails = mapOf("recent" to recentTrail, "stale" to staleTrail)

        val filtered = TrackerMapLiveActiveFitPolicy.filterActiveTrails(
            allQueueTrailsByTracker = trails,
            remoteLastPoints = emptyMap(),
            trackers = emptyList(),
            nowMs = nowMs,
        )

        assertTrue(filtered.containsKey("recent"))
        assertFalse(filtered.containsKey("stale"))
    }

    @Test
    fun filterActiveTrails_usesRemotePointTimestamp() {
        val nowMs = System.currentTimeMillis()
        val staleTrail = listOf(makeQueuedLocation(nowMs - 20 * 60 * 1000L))
        val trails = mapOf("t1" to staleTrail)
        val remotePoints = mapOf(
            "t1" to TrackPointEvent(
                trackId = "t1",
                lat = 0.0,
                lon = 0.0,
                timestampMs = nowMs - 30_000L,
                accuracyMeters = null,
                propsJson = null,
                source = TrackPointSource.REMOTE_STREAM,
            )
        )

        val filtered = TrackerMapLiveActiveFitPolicy.filterActiveTrails(
            allQueueTrailsByTracker = trails,
            remoteLastPoints = remotePoints,
            trackers = emptyList(),
            nowMs = nowMs,
        )

        assertTrue(filtered.containsKey("t1"))
    }

    @Test
    fun filterActiveTrails_fallsBackToAllWhenNoActiveFound() {
        val nowMs = System.currentTimeMillis()
        val staleTrail1 = listOf(makeQueuedLocation(nowMs - 20 * 60 * 1000L))
        val staleTrail2 = listOf(makeQueuedLocation(nowMs - 25 * 60 * 1000L))
        val trails = mapOf("t1" to staleTrail1, "t2" to staleTrail2)

        val filtered = TrackerMapLiveActiveFitPolicy.filterActiveTrails(
            allQueueTrailsByTracker = trails,
            remoteLastPoints = emptyMap(),
            trackers = emptyList(),
            nowMs = nowMs,
        )

        assertEquals(trails, filtered)
    }

    private fun makeQueuedLocation(timeMs: Long): QueuedLocation {
        return QueuedLocation(
            id = 0L,
            trackerId = "test-tracker",
            time = timeMs,
            latitude = 40.0,
            longitude = -74.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = "test",
            dist = null,
        )
    }
}
