package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TrackerMapTrailCommitPolicyTest {
    @Test
    fun recentDataWindowChanged_emptyServerResult_preservesPreviousSingleTrail() {
        val previous = listOf(point("tracker-1", time = 1_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY))

        val result = TrackerMapTrailCommitPolicy.resolve(
            TrackerMapTrailCommitInput(
                reason = TrackerMapTrailReloadReason.RecentDataWindowChanged,
                plan = TrackerMapTrailReloadPlan(
                    source = TrackerMapTrailSource.SINGLE_SERVER,
                    singleTrackerId = "tracker-1",
                    activeTrackerId = "tracker-1",
                ),
                loaded = TrackerMapTrailLoadResult.EMPTY,
                latestState = TrackerMapUiState(
                    mode = TrackerMapDisplayMode.SINGLE_SESSION,
                    trail = previous,
                ),
                trailPointLimit = 10,
            )
        )

        assertEquals(previous, result.trail)
    }

    @Test
    fun historyCleared_dropsQueueFallbackAndPreviousLocalRows() {
        val previous = listOf(
            point("tracker-1", time = 1_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
        )
        val queueFallback = listOf(
            point("tracker-1", time = 2_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
        )

        val result = TrackerMapTrailCommitPolicy.resolve(
            TrackerMapTrailCommitInput(
                reason = TrackerMapTrailReloadReason.HistoryCleared,
                plan = TrackerMapTrailReloadPlan(
                    source = TrackerMapTrailSource.SINGLE_SERVER,
                    singleTrackerId = "tracker-1",
                    activeTrackerId = "tracker-1",
                ),
                loaded = TrackerMapTrailLoadResult(
                    serverTrails = emptyMap(),
                    queueOverlaysByTracker = emptyMap(),
                    singleTrailSeed = queueFallback,
                ),
                latestState = TrackerMapUiState(
                    mode = TrackerMapDisplayMode.SINGLE_SESSION,
                    trail = previous,
                ),
                trailPointLimit = 10,
                clearedHistoryTrackerIds = setOf("tracker-1"),
            )
        )

        assertEquals(emptyList<QueuedLocation>(), result.trail)
    }

    @Test
    fun historyCleared_preservesOnlyActiveSessionLiveOverlay() {
        val activeStart = 10_000L
        val previous = listOf(
            point("tracker-1", time = 1_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS, startTimestampMs = null),
            point("tracker-1", time = 2_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS, startTimestampMs = 5_000L),
            point("tracker-1", time = 3_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS, startTimestampMs = activeStart),
        )

        val result = TrackerMapTrailCommitPolicy.resolve(
            TrackerMapTrailCommitInput(
                reason = TrackerMapTrailReloadReason.HistoryCleared,
                plan = TrackerMapTrailReloadPlan(
                    source = TrackerMapTrailSource.SINGLE_SERVER,
                    singleTrackerId = "tracker-1",
                    activeTrackerId = "tracker-1",
                ),
                loaded = TrackerMapTrailLoadResult.EMPTY,
                latestState = TrackerMapUiState(
                    mode = TrackerMapDisplayMode.SINGLE_SESSION,
                    trail = previous,
                ),
                trailPointLimit = 10,
                activeSessionStartByTracker = mapOf("tracker-1" to activeStart),
                clearedHistoryTrackerIds = setOf("tracker-1"),
            )
        )

        assertEquals(listOf(3_000L), result.trail.map { it.time })
        assertEquals(listOf(activeStart), result.trail.map { it.startTimestampMs })
    }

    @Test
    fun historyCleared_removesClearedTrackerFromMultiTrailsAndFallbacks() {
        val result = TrackerMapTrailCommitPolicy.resolve(
            TrackerMapTrailCommitInput(
                reason = TrackerMapTrailReloadReason.HistoryCleared,
                plan = TrackerMapTrailReloadPlan(
                    source = TrackerMapTrailSource.MULTI_SERVER,
                    trackerIds = setOf("tracker-1", "tracker-2"),
                    activeTrackerId = "tracker-1",
                ),
                loaded = TrackerMapTrailLoadResult(
                    serverTrails = mapOf(
                        "tracker-1" to listOf(point("tracker-1", time = 3_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS)),
                        "tracker-2" to listOf(point("tracker-2", time = 4_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY)),
                    ),
                    queueOverlaysByTracker = emptyMap(),
                    singleTrailSeed = emptyList(),
                ),
                latestState = TrackerMapUiState(
                    mode = TrackerMapDisplayMode.ALL_QUEUE,
                    allQueueTrailsByTracker = mapOf(
                        "tracker-1" to listOf(point("tracker-1", time = 1_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS)),
                        "tracker-2" to listOf(point("tracker-2", time = 2_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY)),
                    ),
                ),
                trailPointLimit = 10,
                clearedHistoryTrackerIds = setOf("tracker-1"),
            )
        )

        assertFalse(result.multiTrails.containsKey("tracker-1"))
        assertEquals(listOf(4_000L), result.multiTrails.getValue("tracker-2").map { it.time })
    }

    @Test
    fun genericMapRefresh_serverSeedPlusQueueOverlay_commitsFullActiveSessionTail() {
        val activeStart = 10_000L
        val server = listOf(
            point("tracker-1", time = 11_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
            point("tracker-1", time = 13_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
        )
        val queueOverlay = listOf(
            point("tracker-1", time = 12_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS, startTimestampMs = activeStart),
            point("tracker-1", time = 14_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS, startTimestampMs = activeStart),
        )

        val result = TrackerMapTrailCommitPolicy.resolve(
            TrackerMapTrailCommitInput(
                reason = TrackerMapTrailReloadReason.GenericMapRefresh,
                plan = TrackerMapTrailReloadPlan(
                    source = TrackerMapTrailSource.SINGLE_SERVER,
                    singleTrackerId = "tracker-1",
                    overlayTrackerId = "tracker-1",
                    activeTrackerId = "tracker-1",
                ),
                loaded = TrackerMapTrailLoadResult(
                    serverTrails = emptyMap(),
                    queueOverlaysByTracker = mapOf("tracker-1" to queueOverlay),
                    singleTrailSeed = server,
                ),
                latestState = TrackerMapUiState(
                    mode = TrackerMapDisplayMode.SINGLE_SESSION,
                    trail = server,
                ),
                trailPointLimit = 10,
                activeSessionStartByTracker = mapOf("tracker-1" to activeStart),
            )
        )

        assertEquals(listOf(11_000L, 12_000L, 13_000L, 14_000L), result.trail.map { it.time })
    }

    @Test
    fun rosterChanged_partialServerSeed_preservesActiveSessionLocalCoverage() {
        val activeStart = 10_000L
        val server = listOf(
            point("tracker-1", time = 11_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY, startTimestampMs = activeStart),
            point("tracker-1", time = 13_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY, startTimestampMs = activeStart),
        )
        val activeLocal = listOf(
            point("tracker-1", time = 12_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS, startTimestampMs = activeStart),
            point("tracker-1", time = 14_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS, startTimestampMs = activeStart),
        )

        val result = TrackerMapTrailCommitPolicy.resolve(
            TrackerMapTrailCommitInput(
                reason = TrackerMapTrailReloadReason.RosterChanged,
                plan = TrackerMapTrailReloadPlan(
                    source = TrackerMapTrailSource.SINGLE_SERVER,
                    singleTrackerId = "tracker-1",
                    overlayTrackerId = "tracker-1",
                    activeTrackerId = "tracker-1",
                ),
                loaded = TrackerMapTrailLoadResult(
                    serverTrails = emptyMap(),
                    queueOverlaysByTracker = emptyMap(),
                    singleTrailSeed = server,
                ),
                latestState = TrackerMapUiState(
                    mode = TrackerMapDisplayMode.SINGLE_SESSION,
                    trail = server + activeLocal,
                ),
                trailPointLimit = 10,
                activeSessionStartByTracker = mapOf("tracker-1" to activeStart),
            )
        )

        assertEquals(listOf(11_000L, 12_000L, 13_000L, 14_000L), result.trail.map { it.time })
    }

    @Test
    fun rosterChanged_emptyServerSeed_preservesValidActiveSessionTrail() {
        val activeStart = 10_000L
        val activeLocal = listOf(
            point("tracker-1", time = 12_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS, startTimestampMs = activeStart),
            point("tracker-1", time = 14_000L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS, startTimestampMs = activeStart),
        )

        val result = TrackerMapTrailCommitPolicy.resolve(
            TrackerMapTrailCommitInput(
                reason = TrackerMapTrailReloadReason.RosterChanged,
                plan = TrackerMapTrailReloadPlan(
                    source = TrackerMapTrailSource.SINGLE_SERVER,
                    singleTrackerId = "tracker-1",
                    overlayTrackerId = "tracker-1",
                    activeTrackerId = "tracker-1",
                ),
                loaded = TrackerMapTrailLoadResult.EMPTY,
                latestState = TrackerMapUiState(
                    mode = TrackerMapDisplayMode.SINGLE_SESSION,
                    trail = activeLocal,
                ),
                trailPointLimit = 10,
                activeSessionStartByTracker = mapOf("tracker-1" to activeStart),
            )
        )

        assertEquals(listOf(12_000L, 14_000L), result.trail.map { it.time })
    }

    private fun point(
        trackerId: String,
        time: Long,
        prov: String,
        startTimestampMs: Long? = null,
    ): QueuedLocation {
        return QueuedLocation(
            id = if (prov == TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY) -time else 0L,
            trackerId = trackerId,
            time = time,
            latitude = time.toDouble() / 1000.0,
            longitude = time.toDouble() / 1000.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = prov,
            dist = null,
            startTimestampMs = startTimestampMs,
        )
    }
}
