package com.geovault.tracker.history

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerHistoryOverlayEligibilityPolicyTest {

    @Test
    fun filterOverlayCandidates_keepsOnlyNewerThanTrunkTail() {
        val trunk = listOf(
            point(time = 100L, sessionStart = 5_000L, provenance = TrackerHistoryProvenance.SERVER_GEOMETRY),
        )
        val overlay = listOf(
            point(time = 90L, sessionStart = null, provenance = TrackerHistoryProvenance.LOCAL_QUEUE),
            point(time = 120L, sessionStart = 5_000L, provenance = TrackerHistoryProvenance.LOCAL_QUEUE),
        )

        val filtered = TrackerHistoryOverlayEligibilityPolicy.filterOverlayCandidates(
            trunkPoints = trunk,
            overlayCandidates = overlay,
            activeSessionStartMs = 5_000L,
        )

        assertEquals(listOf(120L), filtered.map { it.timestampMs })
    }

    @Test
    fun filterOverlayCandidates_keepsNullStartWhenSessionSet() {
        val activeStart = 5_000L
        val trunk = listOf(
            point(time = 100L, sessionStart = activeStart, provenance = TrackerHistoryProvenance.SERVER_GEOMETRY),
        )
        val overlay = listOf(
            point(time = 200L, sessionStart = null, provenance = TrackerHistoryProvenance.LOCAL_QUEUE),
            point(time = 300L, sessionStart = activeStart, provenance = TrackerHistoryProvenance.LOCAL_QUEUE),
        )

        val filtered = TrackerHistoryOverlayEligibilityPolicy.filterOverlayCandidates(
            trunkPoints = trunk,
            overlayCandidates = overlay,
            activeSessionStartMs = activeStart,
        )

        assertEquals(listOf(200L, 300L), filtered.map { it.timestampMs })
    }

    @Test
    fun filterOverlayCandidates_allowsActiveSessionLocalNewerThanTrunk() {
        val activeStart = 5_000L
        val trunk = listOf(
            point(time = 100L, sessionStart = activeStart, provenance = TrackerHistoryProvenance.SERVER_GEOMETRY),
        )
        val overlay = listOf(
            point(time = 150L, sessionStart = activeStart, provenance = TrackerHistoryProvenance.LOCAL_QUEUE),
            point(time = 300L, sessionStart = activeStart, provenance = TrackerHistoryProvenance.LOCAL_QUEUE),
        )

        val filtered = TrackerHistoryOverlayEligibilityPolicy.filterOverlayCandidates(
            trunkPoints = trunk,
            overlayCandidates = overlay,
            activeSessionStartMs = activeStart,
        )

        assertEquals(listOf(150L, 300L), filtered.map { it.timestampMs })
    }

    @Test
    fun filterOverlayCandidates_dropsOverlayFromDifferentSession() {
        val trunk = listOf(
            point(time = 100L, sessionStart = 5_000L, provenance = TrackerHistoryProvenance.SERVER_GEOMETRY),
        )
        val overlay = listOf(
            point(time = 200L, sessionStart = 1_000L, provenance = TrackerHistoryProvenance.LOCAL_QUEUE),
            point(time = 300L, sessionStart = 5_000L, provenance = TrackerHistoryProvenance.LOCAL_QUEUE),
        )

        val filtered = TrackerHistoryOverlayEligibilityPolicy.filterOverlayCandidates(
            trunkPoints = trunk,
            overlayCandidates = overlay,
            activeSessionStartMs = 5_000L,
        )

        assertEquals(listOf(300L), filtered.map { it.timestampMs })
    }

    private fun point(
        time: Long,
        sessionStart: Long?,
        provenance: TrackerHistoryProvenance,
    ): TrackerHistoryPoint {
        return TrackerHistoryPoint(
            trackerId = "local",
            timestampMs = time,
            latitude = 40.0,
            longitude = -105.0,
            accuracy = 5f,
            startTimestampMs = sessionStart,
            provenance = provenance,
        )
    }
}
