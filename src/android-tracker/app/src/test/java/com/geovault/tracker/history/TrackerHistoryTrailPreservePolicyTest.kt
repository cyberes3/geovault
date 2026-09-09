package com.geovault.tracker.history

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.map.MapTrailEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerHistoryTrailPreservePolicyTest {

    @Test
    fun preserveActiveSessionTrailWhenMappedEmpty_keepsStateActiveSessionRows() {
        val activeStart = 10_000L
        val stateTrail = listOf(
            point("tracker-1", time = 12_000L, startTimestampMs = activeStart),
            point("tracker-1", time = 14_000L, startTimestampMs = activeStart),
        )

        val preserved = MapTrailEngine.preserveActiveSessionTrailWhenMappedEmpty(
            mappedTrail = emptyList(),
            stateTrail = stateTrail,
            trackerId = "tracker-1",
            activeSessionStartMs = activeStart,
        )

        assertEquals(listOf(12_000L, 14_000L), preserved.map { it.time })
    }

    @Test
    fun preserveActiveSessionTrailWhenMappedEmpty_nonEmptyMappedUnchanged() {
        val mapped = listOf(point("tracker-1", time = 1_000L))
        val preserved = MapTrailEngine.preserveActiveSessionTrailWhenMappedEmpty(
            mappedTrail = mapped,
            stateTrail = listOf(point("tracker-1", time = 2_000L)),
            trackerId = "tracker-1",
            activeSessionStartMs = 10_000L,
        )
        assertEquals(mapped, preserved)
    }

    @Test
    fun mergeActiveSessionCoverageIntoTrunk_carriesMissingActiveSessionLocalPoints() {
        val activeStart = 10_000L
        val server = listOf(
            point("tracker-1", time = 11_000L, prov = MapTrailEngine.PROVENANCE_SERVER_GEOMETRY, startTimestampMs = activeStart),
            point("tracker-1", time = 13_000L, prov = MapTrailEngine.PROVENANCE_SERVER_GEOMETRY, startTimestampMs = activeStart),
        )
        val current = listOf(
            point("tracker-1", time = 12_000L, prov = MapTrailEngine.PROVENANCE_LOCAL_GPS, startTimestampMs = activeStart),
            point("tracker-1", time = 14_000L, prov = MapTrailEngine.PROVENANCE_LOCAL_GPS, startTimestampMs = activeStart),
        )

        val merged = MapTrailEngine.mergeActiveSessionCoverageIntoTrunk(
            serverTrunk = server,
            currentTrail = current,
            trackerId = "tracker-1",
            activeSessionStartMs = activeStart,
            trailPointLimit = 10,
        )

        assertEquals(listOf(11_000L, 12_000L, 13_000L, 14_000L), merged.map { it.time })
    }

    @Test
    fun mergeActiveSessionCoverageIntoTrunk_emptyServer_preservesActiveLocalOnly() {
        val activeStart = 10_000L
        val activeLocal = listOf(
            point("tracker-1", time = 12_000L, prov = MapTrailEngine.PROVENANCE_LOCAL_GPS, startTimestampMs = activeStart),
            point("tracker-1", time = 14_000L, prov = MapTrailEngine.PROVENANCE_LOCAL_GPS, startTimestampMs = activeStart),
        )

        val merged = MapTrailEngine.mergeActiveSessionCoverageIntoTrunk(
            serverTrunk = emptyList(),
            currentTrail = activeLocal,
            trackerId = "tracker-1",
            activeSessionStartMs = activeStart,
            trailPointLimit = 10,
        )

        assertEquals(listOf(12_000L, 14_000L), merged.map { it.time })
    }

    @Test
    fun mergeActiveSessionCoverageIntoTrunkBatch_addsPointsToServerBatch() {
        val activeStart = 10_000L
        val batch = TrackerHistorySourceBatch(
            trackerId = "tracker-1",
            window = TrackerHistoryWindow("current_session"),
            sourceKind = TrackerHistorySourceKind.FILTERED_SERVER_TRUNK,
            points = listOf(
                historyPoint(11_000L, TrackerHistoryProvenance.SERVER_GEOMETRY, activeStart),
                historyPoint(13_000L, TrackerHistoryProvenance.SERVER_GEOMETRY, activeStart),
            ),
        )
        val current = listOf(
            point("tracker-1", time = 12_000L, prov = MapTrailEngine.PROVENANCE_LOCAL_GPS, startTimestampMs = activeStart),
            point("tracker-1", time = 14_000L, prov = MapTrailEngine.PROVENANCE_LOCAL_GPS, startTimestampMs = activeStart),
        )

        val merged = MapTrailEngine.mergeActiveSessionCoverageIntoTrunkBatch(
            batch = batch,
            currentTrail = current,
            activeSessionStartMs = activeStart,
            trailPointLimit = 10,
        )

        assertEquals(listOf(11_000L, 12_000L, 13_000L, 14_000L), merged.points.map { it.timestampMs })
    }

    private fun point(
        trackerId: String,
        time: Long,
        prov: String = MapTrailEngine.PROVENANCE_LOCAL_GPS,
        startTimestampMs: Long? = null,
    ): QueuedLocation {
        return QueuedLocation(
            id = if (prov == MapTrailEngine.PROVENANCE_SERVER_GEOMETRY) -time else 0L,
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

    private fun historyPoint(
        time: Long,
        provenance: TrackerHistoryProvenance,
        sessionStart: Long?,
    ): TrackerHistoryPoint {
        return TrackerHistoryPoint(
            trackerId = "tracker-1",
            timestampMs = time,
            latitude = time / 1000.0,
            longitude = time / 1000.0,
            startTimestampMs = sessionStart,
            provenance = provenance,
            rowId = -time,
        )
    }
}
