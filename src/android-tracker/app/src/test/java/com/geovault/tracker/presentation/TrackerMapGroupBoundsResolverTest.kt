package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class TrackerMapGroupBoundsResolverTest {

    @Test
    fun strategy_lockOff_allVisible() {
        val strategy = TrackerMapGroupBoundsResolver.strategy(
            baseInput(liveActiveFitEnabled = false, fitOnlyActiveTrackers = true),
        )
        assertEquals(TrackerMapGroupBoundsStrategy.AllVisible, strategy)
    }

    @Test
    fun strategy_lockOn_fitOnlyActive_activeOnly() {
        val strategy = TrackerMapGroupBoundsResolver.strategy(
            baseInput(liveActiveFitEnabled = true, fitOnlyActiveTrackers = true),
        )
        assertEquals(TrackerMapGroupBoundsStrategy.ActiveOnly, strategy)
    }

    @Test
    fun strategy_lockOn_fitAll_allVisibleWhileLocked() {
        val strategy = TrackerMapGroupBoundsResolver.strategy(
            baseInput(liveActiveFitEnabled = true, fitOnlyActiveTrackers = false),
        )
        assertEquals(TrackerMapGroupBoundsStrategy.AllVisibleWhileLocked, strategy)
    }

    @Test
    fun resolve_activeOnly_rosterOnlyLiveTractor_includesLastPoint() {
        val nowMs = System.currentTimeMillis()
        val trackers = listOf(
            Tracker(
                id = "live-roster",
                name = "Live",
                color = null,
                updated_at = (nowMs - 30_000L) / 1000L,
                last_point = listOf(-74.0, 40.0),
            ),
            Tracker(
                id = "stale-roster",
                name = "Stale",
                color = null,
                updated_at = (nowMs - 20 * 60 * 1000L) / 1000L,
                last_point = listOf(-80.0, 35.0),
            ),
        )

        val bounds = TrackerMapGroupBoundsResolver.resolve(
            baseInput(
                liveActiveFitEnabled = true,
                fitOnlyActiveTrackers = true,
                visibleTrackerIds = setOf("live-roster", "stale-roster"),
                trackers = trackers,
                nowMs = nowMs,
            ),
        )

        assertNotNull(bounds)
        assertEquals(40.0, bounds!!.latitudeNorth, 0.0)
        assertEquals(40.0, bounds.latitudeSouth, 0.0)
        assertEquals(-74.0, bounds.longitudeEast, 0.0)
        assertEquals(-74.0, bounds.longitudeWest, 0.0)
    }

    @Test
    fun resolve_activeOnly_excludesTrackerOutsideVisibleIds() {
        val nowMs = System.currentTimeMillis()
        val trackers = listOf(
            Tracker(
                id = "hidden",
                name = "Hidden",
                color = null,
                updated_at = (nowMs - 30_000L) / 1000L,
                last_point = listOf(-74.0, 40.0),
            ),
        )

        val bounds = TrackerMapGroupBoundsResolver.resolve(
            baseInput(
                liveActiveFitEnabled = true,
                fitOnlyActiveTrackers = true,
                visibleTrackerIds = emptySet(),
                trackers = trackers,
                nowMs = nowMs,
            ),
        )

        assertNull(bounds)
    }

    @Test
    fun resolve_activeOnly_noQualifyingTrackers_null() {
        val nowMs = System.currentTimeMillis()
        val staleTrail = listOf(makeQueuedLocation(nowMs - 20 * 60 * 1000L))

        val bounds = TrackerMapGroupBoundsResolver.resolve(
            baseInput(
                liveActiveFitEnabled = true,
                fitOnlyActiveTrackers = true,
                visibleTrackerIds = setOf("t1"),
                trailsByTracker = mapOf("t1" to staleTrail),
                nowMs = nowMs,
            ),
        )

        assertNull(bounds)
    }

    @Test
    fun resolve_allVisible_includesAllRosterLastPoints() {
        val nowMs = System.currentTimeMillis()
        val trackers = listOf(
            Tracker(
                id = "a",
                name = "A",
                color = null,
                updated_at = (nowMs - 20 * 60 * 1000L) / 1000L,
                last_point = listOf(-74.0, 40.0),
            ),
            Tracker(
                id = "b",
                name = "B",
                color = null,
                updated_at = (nowMs - 25 * 60 * 1000L) / 1000L,
                last_point = listOf(-80.0, 35.0),
            ),
        )

        val bounds = TrackerMapGroupBoundsResolver.resolve(
            baseInput(
                liveActiveFitEnabled = false,
                visibleTrackerIds = setOf("a", "b"),
                trackers = trackers,
                nowMs = nowMs,
            ),
        )

        assertNotNull(bounds)
        assertEquals(40.0, bounds!!.latitudeNorth, 0.0)
        assertEquals(35.0, bounds.latitudeSouth, 0.0)
        assertEquals(-74.0, bounds.longitudeEast, 0.0)
        assertEquals(-80.0, bounds.longitudeWest, 0.0)
    }

    @Test
    fun resolve_activeOnly_includesActiveRemoteOnlyHead() {
        val nowMs = System.currentTimeMillis()
        val remotePoints = mapOf(
            "remote" to TrackPointEvent(
                trackId = "remote",
                lat = 12.0,
                lon = 34.0,
                timestampMs = nowMs - 30_000L,
                accuracyMeters = null,
                propsJson = null,
                source = TrackPointSource.REMOTE_STREAM,
            ),
        )

        val bounds = TrackerMapGroupBoundsResolver.resolve(
            baseInput(
                liveActiveFitEnabled = true,
                fitOnlyActiveTrackers = true,
                visibleTrackerIds = setOf("remote"),
                remoteLastPoints = remotePoints,
                acceptedRemoteTrackerIds = setOf("remote"),
                nowMs = nowMs,
            ),
        )

        assertNotNull(bounds)
        assertEquals(12.0, bounds!!.latitudeNorth, 0.0)
        assertEquals(12.0, bounds.latitudeSouth, 0.0)
        assertEquals(34.0, bounds.longitudeEast, 0.0)
        assertEquals(34.0, bounds.longitudeWest, 0.0)
    }

    @Test
    fun resolve_activeOnly_groupStreamingIncludesLocalOverlayAndRemoteHeads() {
        val nowMs = System.currentTimeMillis()
        val remotePoints = mapOf(
            "remote" to TrackPointEvent(
                trackId = "remote",
                lat = 50.0,
                lon = 60.0,
                timestampMs = nowMs - 30_000L,
                accuracyMeters = null,
                propsJson = null,
                source = TrackPointSource.REMOTE_STREAM,
            ),
        )

        val bounds = TrackerMapGroupBoundsResolver.resolve(
            baseInput(
                liveActiveFitEnabled = true,
                fitOnlyActiveTrackers = true,
                visibleTrackerIds = setOf("local", "remote"),
                trailsByTracker = mapOf("local" to listOf(makeQueuedLocation(nowMs - 20_000L))),
                remoteLastPoints = remotePoints,
                acceptedRemoteTrackerIds = setOf("remote"),
                nowMs = nowMs,
            ),
        )

        assertNotNull(bounds)
        assertEquals(50.0, bounds!!.latitudeNorth, 0.0)
        assertEquals(40.0, bounds.latitudeSouth, 0.0)
        assertEquals(60.0, bounds.longitudeEast, 0.0)
        assertEquals(-74.0, bounds.longitudeWest, 0.0)
    }

    @Test
    fun resolve_activeOnly_keepsAcceptedRemoteTrailsWhileLocalIsOnlyFreshPoint() {
        val nowMs = System.currentTimeMillis()
        val oldRemoteTrail = listOf(
            makeQueuedLocation(
                timeMs = nowMs - 30 * 60 * 1000L,
                latitude = 50.0,
                longitude = 60.0,
            ),
        )

        val bounds = TrackerMapGroupBoundsResolver.resolve(
            baseInput(
                liveActiveFitEnabled = true,
                fitOnlyActiveTrackers = true,
                visibleTrackerIds = setOf("local", "remote"),
                trailsByTracker = mapOf(
                    "local" to listOf(makeQueuedLocation(nowMs - 20_000L)),
                    "remote" to oldRemoteTrail,
                ),
                acceptedRemoteTrackerIds = setOf("remote"),
                nowMs = nowMs,
            ),
        )

        assertNotNull(bounds)
        assertEquals(50.0, bounds!!.latitudeNorth, 0.0)
        assertEquals(40.0, bounds.latitudeSouth, 0.0)
        assertEquals(60.0, bounds.longitudeEast, 0.0)
        assertEquals(-74.0, bounds.longitudeWest, 0.0)
    }

    @Test
    fun resolve_activeOnly_usesTrackerUpdatedAtForVisibleRosterOnly() {
        val nowMs = System.currentTimeMillis()
        val trackers = listOf(
            Tracker(
                id = "t1",
                name = "T1",
                color = null,
                updated_at = (nowMs - 30_000L) / 1000L,
                last_point = listOf(-74.0, 40.0),
            ),
        )

        val bounds = TrackerMapGroupBoundsResolver.resolve(
            baseInput(
                liveActiveFitEnabled = true,
                fitOnlyActiveTrackers = true,
                visibleTrackerIds = setOf("t1"),
                trailsByTracker = mapOf("t1" to listOf(makeQueuedLocation(nowMs - 20 * 60 * 1000L))),
                trackers = trackers,
                nowMs = nowMs,
            ),
        )

        assertNotNull(bounds)
        assertTrue(bounds!!.latitudeNorth >= 40.0)
    }

    @Test
    fun resolve_activeOnly_remoteOutsideTenMinuteWindow_stillPinnedWhenAccepted() {
        val nowMs = System.currentTimeMillis()
        val remotePoints = mapOf(
            "remote" to TrackPointEvent(
                trackId = "remote",
                lat = 12.0,
                lon = 34.0,
                timestampMs = nowMs - 11 * 60 * 1000L,
                accuracyMeters = null,
                propsJson = null,
                source = TrackPointSource.REMOTE_STREAM,
            ),
        )

        val bounds = TrackerMapGroupBoundsResolver.resolve(
            baseInput(
                liveActiveFitEnabled = true,
                fitOnlyActiveTrackers = true,
                visibleTrackerIds = setOf("remote"),
                remoteLastPoints = remotePoints,
                acceptedRemoteTrackerIds = setOf("remote"),
                nowMs = nowMs,
            ),
        )

        assertNotNull(bounds)
        assertEquals(12.0, bounds!!.latitudeNorth, 0.0)
        assertEquals(34.0, bounds.longitudeEast, 0.0)
    }

    @Test
    fun resolve_activeOnly_ignoresUnacceptedRemotePointTimestamp() {
        val nowMs = System.currentTimeMillis()
        val staleTrail = listOf(makeQueuedLocation(nowMs - 20 * 60 * 1000L))
        val remotePoints = mapOf(
            "t1" to TrackPointEvent(
                trackId = "t1",
                lat = 0.0,
                lon = 0.0,
                timestampMs = nowMs - 30_000L,
                accuracyMeters = null,
                propsJson = null,
                source = TrackPointSource.REMOTE_STREAM,
            ),
        )

        val bounds = TrackerMapGroupBoundsResolver.resolve(
            baseInput(
                liveActiveFitEnabled = true,
                fitOnlyActiveTrackers = true,
                visibleTrackerIds = setOf("t1"),
                trailsByTracker = mapOf("t1" to staleTrail),
                remoteLastPoints = remotePoints,
                acceptedRemoteTrackerIds = emptySet(),
                nowMs = nowMs,
            ),
        )

        assertNull(bounds)
    }

    @Test
    fun resolve_allVisibleWhileLocked_mergesTrailsAcrossVisibleTrackers() {
        val nowMs = System.currentTimeMillis()

        val bounds = TrackerMapGroupBoundsResolver.resolve(
            baseInput(
                liveActiveFitEnabled = true,
                fitOnlyActiveTrackers = false,
                visibleTrackerIds = setOf("x", "y"),
                trailsByTracker = mapOf(
                    "x" to listOf(makeQueuedLocation(nowMs, latitude = 0.0, longitude = 0.0)),
                    "y" to listOf(makeQueuedLocation(nowMs, latitude = 6.0, longitude = 6.0)),
                ),
                nowMs = nowMs,
            ),
        )

        assertNotNull(bounds)
        assertEquals(6.0, bounds!!.latitudeNorth, 0.0)
        assertEquals(0.0, bounds.latitudeSouth, 0.0)
        assertEquals(6.0, bounds.longitudeEast, 0.0)
        assertEquals(0.0, bounds.longitudeWest, 0.0)
    }

    private fun baseInput(
        visibleTrackerIds: Set<String> = emptySet(),
        liveActiveFitEnabled: Boolean = false,
        fitOnlyActiveTrackers: Boolean = true,
        trailsByTracker: Map<String, List<QueuedLocation>> = emptyMap(),
        remoteLastPoints: Map<String, TrackPointEvent> = emptyMap(),
        acceptedRemoteTrackerIds: Set<String> = remoteLastPoints.keys,
        trackers: List<Tracker> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ): TrackerMapGroupBoundsInput {
        return TrackerMapGroupBoundsInput(
            visibleTrackerIds = visibleTrackerIds,
            liveActiveFitEnabled = liveActiveFitEnabled,
            fitOnlyActiveTrackers = fitOnlyActiveTrackers,
            trailsByTracker = trailsByTracker,
            remoteLastPoints = remoteLastPoints,
            acceptedRemoteTrackerIds = acceptedRemoteTrackerIds,
            trackers = trackers,
            nowMs = nowMs,
        )
    }

    private fun makeQueuedLocation(
        timeMs: Long,
        latitude: Double = 40.0,
        longitude: Double = -74.0,
    ): QueuedLocation {
        return QueuedLocation(
            id = 0L,
            trackerId = "test-tracker",
            time = timeMs,
            latitude = latitude,
            longitude = longitude,
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
